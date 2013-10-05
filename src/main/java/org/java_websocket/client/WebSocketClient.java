package org.java_websocket.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.NotYetConnectedException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

import org.java_websocket.IWebSocket;
import org.java_websocket.IWebSocket.READYSTATE;
import org.java_websocket.SocketChannelIOHelper;
import org.java_websocket.WebSocket;
import org.java_websocket.WebSocketAdapter;
import org.java_websocket.WebSocketFactory;
import org.java_websocket.WebSocketImpl;
import org.java_websocket.WrappedByteChannel;
import org.java_websocket.drafts.Draft;
import org.java_websocket.drafts.Draft_10;
import org.java_websocket.exceptions.InvalidHandshakeException;
import org.java_websocket.framing.CloseFrame;
import org.java_websocket.handshake.HandshakeImpl1Client;
import org.java_websocket.handshake.Handshakedata;
import org.java_websocket.handshake.ServerHandshake;

/**
 * The <tt>WebSocketClient</tt> is an abstract class that expects a valid
 * "ws://" URI to connect to. When connected, an instance recieves important
 * events related to the life of the connection. A subclass must implement
 * <var>onOpen</var>, <var>onClose</var>, and <var>onMessage</var> to be
 * useful. An instance can send messages to it's connected server via the
 * <var>send</var> method.
 * 
 * @author Nathan Rajlich
 */
public abstract class WebSocketClient extends WebSocketAdapter implements Runnable, IWebSocketClient {

	/**
	 * The URI this channel is supposed to connect to.
	 */
	private URI uri = null;

	private WebSocketImpl conn = null;
	/**
	 * The SocketChannel instance this channel uses.
	 */
	private SocketChannel channel = null;

	private ByteChannel wrappedchannel = null;

	private SelectionKey key = null;
	/**
	 * The 'Selector' used to get event keys from the underlying socket.
	 */
	private Selector selector = null;

	private Thread thread;

	private Draft draft;

	private Map<String,String> headers;

	private CountDownLatch connectLatch = new CountDownLatch( 1 );

	private CountDownLatch closeLatch = new CountDownLatch( 1 );

	private int timeout = 0;

	WebSocketClientFactory wf = new WebSocketClientFactory() {
		@Override
		public IWebSocket createWebSocket( WebSocketAdapter a, Draft d, Socket s ) {
			return new WebSocketImpl( WebSocketClient.this, d, s );
		}

		@Override
		public IWebSocket createWebSocket( WebSocketAdapter a, List<Draft> d, Socket s ) {
			return new WebSocketImpl( WebSocketClient.this, d, s );
		}

		@Override
		public ByteChannel wrapChannel( SelectionKey c, String host, int port ) {
			return (ByteChannel) c.channel();
		}
	};

	public WebSocketClient( URI serverURI ) {
		this( serverURI, new Draft_10() );
	}

	/**
	 * Constructs a WebSocketClient instance and sets it to the connect to the
	 * specified URI. The channel does not attampt to connect automatically. You
	 * must call <var>connect</var> first to initiate the socket connection.
	 */
	public WebSocketClient( URI serverUri , Draft draft ) {
		this( serverUri, draft, null, 0 );
	}

	public WebSocketClient( URI serverUri , Draft draft , Map<String,String> headers , int connecttimeout ) {
		if( serverUri == null ) {
			throw new IllegalArgumentException();
		}
		if( draft == null ) {
			throw new IllegalArgumentException( "null as draft is permitted for `WebSocketServer` only!" );
		}
		this.uri = serverUri;
		this.draft = draft;
		this.headers = headers;
		this.timeout = connecttimeout;
	}

	/**
	 * Gets the URI that this WebSocketClient is connected to.
	 * 
	 * @return The <tt>URI</tt> for this WebSocketClient.
	 */
	@Override
	public URI getURI() {
		return uri;
	}

	/** Returns the protocol version this channel uses. */
	@Override
	public Draft getDraft() {
		return draft;
	}

	/**
	 * Starts a background thread that attempts and maintains a WebSocket
	 * connection to the URI specified in the constructor or via <var>setURI</var>.
	 * <var>setURI</var>.
	 */
	@Override
	public void connect() {
		if( thread != null )
			throw new IllegalStateException( "WebSocketClient objects are not reuseable" );
		thread = new Thread( this );
		thread.start();
	}

	/**
	 * Same as connect but blocks until the websocket connected or failed to do so.<br>
	 * Returns whether it succeeded or not.
	 **/
	@Override
	public boolean connectBlocking() throws InterruptedException {
		connect();
		connectLatch.await();
		return conn.isOpen();
	}

	@Override
	public void close() {
		if( thread != null && conn != null ) {
			conn.close( CloseFrame.NORMAL );
		}
	}

	@Override
	public void closeBlocking() throws InterruptedException {
		close();
		closeLatch.await();
	}

	/**
	 * Sends <var>text</var> to the connected WebSocket server.
	 * 
	 * @param text
	 *            The String to send to the WebSocket server.
	 */
	@Override
	public void send( String text ) throws NotYetConnectedException {
		if( conn != null ) {
			conn.send( text );
		}
	}

	/**
	 * Sends <var>data</var> to the connected WebSocket server.
	 * 
	 * @param data
	 *            The Byte-Array of data to send to the WebSocket server.
	 */
	@Override
	public void send( byte[] data ) throws NotYetConnectedException {
		if( conn != null ) {
			conn.send( data );
		}
	}

	private void tryToConnect( InetSocketAddress remote ) throws IOException {
		channel = SocketChannel.open();
		channel.configureBlocking( false );
		channel.connect( remote );
		selector = Selector.open();
		key = channel.register( selector, SelectionKey.OP_CONNECT );
	}

	// Runnable IMPLEMENTATION /////////////////////////////////////////////////
	public void run() {
		if( thread == null )
			thread = Thread.currentThread();
		interruptableRun();

		assert ( !channel.isOpen() );

		try {
			if( selector != null ) // if the initialization in <code>tryToConnect</code> fails, it could be null
				selector.close();
		} catch ( IOException e ) {
			onError( e );
		}

	}

	private final void interruptableRun() {
		try {
			tryToConnect( new InetSocketAddress( uri.getHost(), getPort() ) );
		} catch ( ClosedByInterruptException e ) {
			onWebsocketError( null, e );
			return;
		} catch ( /*IOException | SecurityException | UnresolvedAddressException*/Exception e ) {//
			onWebsocketError( conn, e );
			conn.closeConnection( CloseFrame.NEVER_CONNECTED, e.getMessage() );
			return;
		}
		conn = (WebSocketImpl) wf.createWebSocket( this, draft, channel.socket() );
		ByteBuffer buff = ByteBuffer.allocate( WebSocket.RCVBUF );
		try/*IO*/{
			while ( channel.isOpen() ) {
				SelectionKey key = null;
				selector.select( timeout );
				Set<SelectionKey> keys = selector.selectedKeys();
				Iterator<SelectionKey> i = keys.iterator();
				if( conn.getReadyState() == READYSTATE.NOT_YET_CONNECTED && !i.hasNext() ) {
					// Hack for issue #140:
					// Android does simply return form select without closing the channel if address is not reachable(which seems to be a bug in the android nio proivder)
					// TODO provide a way to fix this problem which does not require this hack
					throw new IOException( "Host is not reachable(Android Hack)" );
				}
				while ( i.hasNext() ) {
					key = i.next();
					i.remove();
					if( !key.isValid() ) {
						conn.eot();
						continue;
					}
					if( key.isReadable() && SocketChannelIOHelper.read( buff, this.conn, wrappedchannel ) ) {
						conn.decode( buff );
					}
					if( key.isConnectable() ) {
						try {
							finishConnect( key );
						} catch ( InvalidHandshakeException e ) {
							conn.close( e ); // http error
						}
					}
					if( key.isWritable() ) {
						if( SocketChannelIOHelper.batch( conn, wrappedchannel ) ) {
							if( key.isValid() )
								key.interestOps( SelectionKey.OP_READ );
						} else {
							key.interestOps( SelectionKey.OP_READ | SelectionKey.OP_WRITE );
						}
					}
				}
				if( wrappedchannel instanceof WrappedByteChannel ) {
					WrappedByteChannel w = (WrappedByteChannel) wrappedchannel;
					if( w.isNeedRead() ) {
						while ( SocketChannelIOHelper.read( buff, conn, w ) ) {
							conn.decode( buff );
						}
					}
				}
			}

		} catch ( CancelledKeyException e ) {
			conn.eot();
		} catch ( IOException e ) {
			conn.eot();
		} catch ( RuntimeException e ) {
			// this catch case covers internal errors only and indicates a bug in this websocket implementation
			onError( e );
			conn.closeConnection( CloseFrame.ABNORMAL_CLOSE, e.getMessage() );
		}
	}

	private int getPort() {
		int port = uri.getPort();
		if( port == -1 ) {
			String scheme = uri.getScheme();
			if( scheme.equals( "wss" ) ) {
				return IWebSocket.DEFAULT_WSS_PORT;
			} else if( scheme.equals( "ws" ) ) {
				return IWebSocket.DEFAULT_PORT;
			} else {
				throw new RuntimeException( "unkonow scheme" + scheme );
			}
		}
		return port;
	}

	private void finishConnect( SelectionKey key ) throws IOException , InvalidHandshakeException {
		if( !channel.finishConnect() )
			return;
		// Now that we're connected, re-register for only 'READ' keys.
		conn.key = key.interestOps( SelectionKey.OP_READ | SelectionKey.OP_WRITE );

		conn.channel = wrappedchannel = wf.wrapChannel( key, uri.getHost(), getPort() );
		timeout = 0; // since connect is over
		sendHandshake();
	}

	private void sendHandshake() throws InvalidHandshakeException {
		String path;
		String part1 = uri.getPath();
		String part2 = uri.getQuery();
		if( part1 == null || part1.length() == 0 )
			path = "/";
		else
			path = part1;
		if( part2 != null )
			path += "?" + part2;
		int port = getPort();
		String host = uri.getHost() + ( port != IWebSocket.DEFAULT_PORT ? ":" + port : "" );

		HandshakeImpl1Client handshake = new HandshakeImpl1Client();
		handshake.setResourceDescriptor( path );
		handshake.put( "Host", host );
		if( headers != null ) {
			for( Map.Entry<String,String> kv : headers.entrySet() ) {
				handshake.put( kv.getKey(), kv.getValue() );
			}
		}
		conn.startHandshake( handshake );
	}

	/**
	 * This represents the state of the connection.
	 * You can use this method instead of
	 */
	@Override
	public READYSTATE getReadyState() {
		if( conn == null ) {
			return READYSTATE.NOT_YET_CONNECTED;
		}
		return conn.getReadyState();
	}

	/**
	 * Calls subclass' implementation of <var>onMessage</var>.
	 * 
	 * @param conn
	 * @param message
	 */
	@Override
	public final void onWebsocketMessage( IWebSocket conn, String message ) {
		onMessage( message );
	}

	@Override
	public final void onWebsocketMessage( IWebSocket conn, ByteBuffer blob ) {
		onMessage( blob );
	}

	/**
	 * Calls subclass' implementation of <var>onOpen</var>.
	 * 
	 * @param conn
	 */
	@Override
	public final void onWebsocketOpen( IWebSocket conn, Handshakedata handshake ) {
		connectLatch.countDown();
		onOpen( (ServerHandshake) handshake );
	}

	/**
	 * Calls subclass' implementation of <var>onClose</var>.
	 * 
	 * @param conn
	 */
	@Override
	public final void onWebsocketClose( IWebSocket conn, int code, String reason, boolean remote ) {
		connectLatch.countDown();
		closeLatch.countDown();
		onClose( code, reason, remote );
	}

	/**
	 * Calls subclass' implementation of <var>onIOError</var>.
	 * 
	 * @param conn
	 */
	@Override
	public final void onWebsocketError( IWebSocket conn, Exception ex ) {
		onError( ex );
	}

	@Override
	public final void onWriteDemand( IWebSocket conn ) {
		try {
			key.interestOps( SelectionKey.OP_READ | SelectionKey.OP_WRITE );
			selector.wakeup();
		} catch ( CancelledKeyException e ) {
			// since such an exception/event will also occur on the selector there is no need to do anything herec
		}
	}

	@Override
	public void onWebsocketCloseInitiated( IWebSocket conn, int code, String reason ) {
		onCloseInitiated( code, reason );
	}

	@Override
	public void onWebsocketClosing( IWebSocket conn, int code, String reason, boolean remote ) {
		onClosing( code, reason, remote );
	}

	@Override
	public void onCloseInitiated( int code, String reason ) {
	}

	@Override
	public void onClosing( int code, String reason, boolean remote ) {
	}

	@Override
	public IWebSocket getConnection() {
		return conn;
	}

	@Override
	public final void setWebSocketFactory( WebSocketClientFactory wsf ) {
		this.wf = wsf;
	}

	@Override
	public final WebSocketFactory getWebSocketFactory() {
		return wf;
	}

	// ABTRACT METHODS /////////////////////////////////////////////////////////
	@Override
	public abstract void onOpen( ServerHandshake handshakedata );
	@Override
	public abstract void onMessage( String message );
	@Override
	public abstract void onClose( int code, String reason, boolean remote );
	@Override
	public abstract void onError( Exception ex );
	@Override
	public void onMessage( ByteBuffer bytes ) {
	};

	public interface WebSocketClientFactory extends WebSocketFactory {
		public ByteChannel wrapChannel( SelectionKey key, String host, int port ) throws IOException;
	}
}
