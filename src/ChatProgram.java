/**
 * Created by Zortrox on 2/22/2016.
 */

import java.io.*;
import java.lang.reflect.Array;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.concurrent.Semaphore;

public class ChatProgram extends Thread{

	//TCP data wrapper
	private class Message {
		byte[] mData;		//actual data
	}

	private int mType;
	private int mPort;
	private String mIP;
	private boolean mIsFirst;

	private Semaphore mutex = new Semaphore(1);
	private ArrayList<Socket> arrClients = new ArrayList<>();

	public static void main(String[] args) {
		if (args.length > 0) {

			String loc = args[0];
			int port = Integer.parseInt(loc.substring(loc.indexOf(':') + 1));
			String IP = loc.substring(0, loc.indexOf(':'));

			boolean isFirst = true;
			if (args.length > 1) isFirst = Boolean.valueOf(args[1]);

			System.out.println("Starting Client in TCP\n");

			ChatProgram client = new ChatProgram(port, IP, isFirst);
		}
	}

	private ChatProgram(int port, String IP, boolean isFirst) {
		mPort = port;
		mIP = IP;
		mIsFirst = isFirst;

		try {
			TCPConnection();
		} catch (Exception ex) {
			ex.printStackTrace();
		}
	}

	private void TCPConnection() throws Exception {
		Socket socket = null;
		boolean bServerFound = false;

		//keep trying to connect to server
		while(!bServerFound && !mIsFirst)
		{
			try
			{
				socket = new Socket(mIP, mPort);
				bServerFound = true;
			}
			catch(ConnectException e)
			{
				System.out.println("Server refused, retrying...");

				try
				{
					Thread.sleep(2000); //2 seconds
				}
				catch(InterruptedException ex){
					ex.printStackTrace();
				}
			}
		}
		final Socket listenSocket = socket;

		//receive data from all clients
		ServerSocket serverSocket = new ServerSocket(mPort);
		Thread serverThread = new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					processConnections(serverSocket, listenSocket);
				}
				catch (Exception e) {
					e.printStackTrace();
				}
			}
		});
		serverThread.start();

		//receive data from server
		if (!mIsFirst) {
			Thread listenThread = new Thread(new Runnable() {
				@Override
				public void run() {
					try {
						Message msg = new Message();
						String msgSend = "";
						String msgReceive = "";

						while (true) {
							receiveTCPData(listenSocket, msg);
							msgReceive = new String(msg.mData);
							System.out.println("<client>: " + msgReceive);

							//send to all clients
							mutex.acquire();
							for (int i = 0; i < arrClients.size(); i++) {
								sendTCPData(arrClients.get(i), msg);
							}
							mutex.release();
						}
					} catch (Exception e) {
						e.printStackTrace();
					}
				}
			});
			listenThread.start();
		}

		//attach reader to console
		BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));

		Message msg = new Message();
		String msgSend = "";
		String msgReceive = "";

		//keep getting user input
		//send data, receive server data
		while (!msgSend.toLowerCase().equals("exit"))	{
			System.out.print("Text to Send: ");
			msgSend = reader.readLine();
			msg.mData = msgSend.getBytes();

			//send data to server
			if (!mIsFirst) {
				sendTCPData(socket, msg);
			}

			//send data to all clients
			mutex.acquire();
			for (int i = 0; i < arrClients.size(); i++) {
				sendTCPData(arrClients.get(i), msg);
			}
			mutex.release();
		}

		//close socket if user "exits"
		socket.close();
	}

	void processConnections(ServerSocket serverSocket, Socket socket) throws Exception{
		//process requests (read/send data)
		while (true)
		{
			//wait for new connection
			Socket clientSocket = serverSocket.accept();
			System.out.println("<server>: New connection.");

			//create new thread to listen
			Thread thrConn = new Thread(new Runnable() {
				@Override
				public void run() {
					try {
						String recMsg = "";

						//keep reading data until user "exits"
						while (!recMsg.toLowerCase().equals("exit")) {
							//receive the data
							Message msg = new Message();
							receiveTCPData(clientSocket, msg);

							//send back capitalized text
							recMsg = new String(msg.mData);
							if (recMsg.toLowerCase().equals("exit")) {
								System.out.println("[client disconnecting]");
								msg.mData = "Goodbye".getBytes();
							} else {
								System.out.println("<client>: " + recMsg);
							}

							//send data to all other clients
							mutex.acquire();
							for (int i = 0; i < arrClients.size(); i++) {
								if (!arrClients.get(i).equals(clientSocket)) {
									sendTCPData(arrClients.get(i), msg);
								}
							}
							mutex.release();

							//send data to server
							if (!mIsFirst) {
								sendTCPData(socket, msg);
							}
						}
					}
					catch(Exception ex) {
						ex.printStackTrace();
					}
				}
			});
			thrConn.start();	//start the thread
		}
	}

	//handles receiving TCP data and wraps the message in a Message class
	void receiveTCPData(Socket socket, Message msg) throws Exception{
		DataInputStream inData = new DataInputStream(socket.getInputStream());

		//get size of receiving data
		byte[] byteSize = new byte[4];
		inData.readFully(byteSize);
		ByteBuffer bufSize = ByteBuffer.wrap(byteSize);
		int dataSize = bufSize.getInt();

		//receive data
		msg.mData = new byte[dataSize];
		inData.readFully(msg.mData);
	}

	//handles sending wrapped data in the Message class over TCP
	void sendTCPData(Socket socket, Message msg) throws Exception{
		DataOutputStream outData = new DataOutputStream(socket.getOutputStream());

		//wrap size of data
		ByteBuffer b = ByteBuffer.allocate(4);
		b.putInt(msg.mData.length);
		byte[] dataSize = b.array();
		outData.write(dataSize);

		//send data
		outData.write(msg.mData);
		outData.flush();
	}
}
