package org.lantern;

import static org.jboss.netty.buffer.ChannelBuffers.copiedBuffer;
import static org.jboss.netty.buffer.ChannelBuffers.wrappedBuffer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.Socket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.io.IOUtils;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.handler.codec.http.HttpChunk;
import org.jboss.netty.handler.codec.http.HttpChunkTrailer;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.util.CharsetUtil;
import org.lastbamboo.common.p2p.P2PClient;
import org.littleshoot.proxy.KeyStoreManager;
import org.littleshoot.util.ByteBufferUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP request processor that sends requests to peers.
 */
public class PeerHttpRequestProcessor implements HttpRequestProcessor {

    private final Logger log = LoggerFactory.getLogger(getClass());
    
    //space ' '
    static final byte SP = 32;
    
    /**
     * Colon ':'
     */
     static final byte COLON = 58;
    
    /**
     * Carriage return
     */
    static final byte CR = 13;

    /**
     * Equals '='
     */
    static final byte EQUALS = 61;

    /**
     * Line feed character
     */
    static final byte LF = 10;

    /**
     * carriage return line feed
     */
    static final byte[] CRLF = new byte[] { CR, LF };
    
    private static final ChannelBuffer LAST_CHUNK =
        copiedBuffer("0\r\n\r\n", CharsetUtil.US_ASCII);
    
    private URI peerUri;
    private final ProxyStatusListener proxyStatusListener;
    private final P2PClient p2pClient;
    
    /**
     * Map recording the number of consecutive connection failures for a
     * given peer. Note that a successful connection will reset this count
     * back to zero.
     */
    private static Map<URI, AtomicInteger> peerFailureCount =
        new ConcurrentHashMap<URI, AtomicInteger>();

    private final Proxy proxy;

    private boolean chunked;

    private final AtomicReference<Socket> socketRef =
        new AtomicReference<Socket>();
    
    private final KeyStoreManager keyStoreManager;

    private volatile boolean startedCopying;

    public PeerHttpRequestProcessor(final Proxy proxy, 
        final ProxyStatusListener proxyStatusListener,
        final P2PClient p2pClient, final KeyStoreManager keyStoreManager) {
        this.proxy = proxy;
        this.proxyStatusListener = proxyStatusListener;
        this.p2pClient = p2pClient;
        this.keyStoreManager = keyStoreManager;
    }

    @Override
    public boolean hasProxy() {
        if (this.socketRef.get() != null) {
            return true;
        }
        this.peerUri = this.proxy.getPeerProxy();
        if (this.peerUri != null) {
            threadedPeerSocket(this.peerUri);
        } else {
            log.info("No peer proxies!");
        }
        return false;
    }
    
    private void threadedPeerSocket(final URI peer) {
        final Thread thread = new Thread(new Runnable() {

            @Override
            public void run() {
                try {
                    final Socket sock = LanternUtils.openOutgoingPeerSocket(
                        peer, proxyStatusListener, p2pClient, 
                        peerFailureCount);
                    socketRef.set(sock);
                } catch (final IOException e) {
                    log.info("Could not create peer socket");
                }                
            }
            
        }, "Peer-Socket-Connection-Thread");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void processRequest(final Channel browserToProxyChannel,
        final ChannelHandlerContext ctx, final MessageEvent me) 
        throws IOException {
        if (!startedCopying) {
            // We tell the socket not to record stats here because traffic
            // returning to the browser still goes through our encoder 
            // here (i.e. we haven't stripped the encoder to support 
            // CONNECT traffic).
            LanternUtils.startReading(this.socketRef.get(), 
                browserToProxyChannel, false);
            startedCopying = true;
        }

        final HttpRequest request = (HttpRequest) me.getMessage();
        this.chunked = LanternUtils.isTransferEncodingChunked(request);
        
        final byte[] data;
        try {
            data = LanternUtils.toByteBuffer(request, ctx);
        } catch (final Exception e) {
            log.error("Could not encode request?", e);
            return;
        }
        try {
            log.info("Writing {}", new String(data));
            final OutputStream os = this.socketRef.get().getOutputStream();
            os.write(data);
        } catch (final IOException e) {
            // They probably just closed the connection, as they will in
            // many cases.
            
            // Note that we don't record this "failure," as it's frequently
            // not a failure. We instead actually remove peers from our
            // peer proxy list if we can't connect to them in addition to
            // removing them when we detect they're unavailable through XMPP.
        }
    }

    @Override
    public void processChunk(final ChannelHandlerContext ctx, 
        final MessageEvent me) throws IOException {
        // We need to convert the Netty message to raw bytes for sending over
        // the socket.
        final HttpChunk chunk = (HttpChunk) me.getMessage();
        final ChannelBuffer cb = encodeChunk(chunk);
        if (cb == null) {
            return;
        }
        
        final ByteBuffer buf = cb.toByteBuffer();
        final byte[] data = ByteBufferUtils.toRawBytes(buf);
        log.info("Writing chunk {}", new String(data));
        final OutputStream os = this.socketRef.get().getOutputStream();
        os.write(data);
    }
    
    private ChannelBuffer encodeChunk(final HttpChunk chunk) {
        if (chunked) {
            if (chunk.isLast()) {
                // We create new chunk writers every time, so we don't need to 
                // reset the chunk flag.
                //chunked = false;
                if (chunk instanceof HttpChunkTrailer) {
                    ChannelBuffer trailer = ChannelBuffers.dynamicBuffer();
                    trailer.writeByte((byte) '0');
                    trailer.writeByte(CR);
                    trailer.writeByte(LF);
                    encodeTrailingHeaders(trailer, (HttpChunkTrailer) chunk);
                    trailer.writeByte(CR);
                    trailer.writeByte(LF);
                    return trailer;
                } else {
                    return LAST_CHUNK.duplicate();
                }
            } else {
                ChannelBuffer content = chunk.getContent();
                int contentLength = content.readableBytes();

                return wrappedBuffer(
                        copiedBuffer(
                                Integer.toHexString(contentLength),
                                CharsetUtil.US_ASCII),
                        wrappedBuffer(CRLF),
                        content.slice(content.readerIndex(), contentLength),
                        wrappedBuffer(CRLF));
            }
        } else {
            if (chunk.isLast()) {
                return null;
            } else {
                return chunk.getContent();
            }
        }
    }
    
    private void encodeTrailingHeaders(final ChannelBuffer buf, 
        final HttpChunkTrailer trailer) {
        try {
            for (final Map.Entry<String, String> h: trailer.getHeaders()) {
                encodeHeader(buf, h.getKey(), h.getValue());
            }
        } catch (final UnsupportedEncodingException e) {
            throw (Error) new Error().initCause(e);
        }
    }

    private void encodeHeader(ChannelBuffer buf, String header, String value)
            throws UnsupportedEncodingException {
        buf.writeBytes(header.getBytes("ASCII"));
        buf.writeByte(COLON);
        buf.writeByte(SP);
        buf.writeBytes(value.getBytes("ASCII"));
        buf.writeByte(CR);
        buf.writeByte(LF);
    }

    @Override
    public void close() {
        IOUtils.closeQuietly(this.socketRef.get());
    }
}
