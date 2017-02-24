/**
 * Created by Zortrox on 2/22/2016.
 */

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.text.DefaultCaret;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.concurrent.Semaphore;

public class ChatProgram {

	//TCP data wrapper
	private class Message {
		byte[] mData;		//actual data
	}

	private int mClientPort;
	private int mServerPort;
	private String mIP;
	private boolean mIsFirst;

	private Socket clientSocket;
	private ServerSocket serverSocket;

	private String clientName = "";
	private JTextArea txtReceive;
	private JScrollPane scrollPane;

	private Semaphore mtxClient = new Semaphore(1);
	private Semaphore mtxSendServer = new Semaphore(1);
	private Semaphore mtxTextArea = new Semaphore(1);

	private ArrayList<Socket> arrClients = new ArrayList<>();

	public static void main(String[] args) {
		if (args.length > 0) {

			String loc = args[0];
			int port = Integer.parseInt(loc.substring(loc.indexOf(':') + 1));
			String IP = loc.substring(0, loc.indexOf(':'));

			boolean isFirst = false;
			int serverPort = port;
			if (args.length > 1) {
				if (args[1].toLowerCase().equals("true")) {
					isFirst = true;
				} else {
					try {
						serverPort = Integer.parseInt(args[1]);
					} catch (NumberFormatException e) {
						serverPort = port;
					}
				}
			}

			System.out.println("Starting Client in TCP\n");

			ChatProgram client = new ChatProgram(port, serverPort, IP, isFirst);

			try {
				client.TCPConnection();
			} catch (Exception ex) {
				System.out.println("[Network Error Occurred]");
			}
		}
	}

	private ChatProgram(int portClient, int portServer, String IP, boolean isFirst) {
		mClientPort = portClient;
		mServerPort = portServer;
		mIP = IP;
		mIsFirst = isFirst;

		JFrame frame = new JFrame("Chat Client");
		frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);

		txtReceive = new JTextArea(1, 20);
		txtReceive.setEditable(false);
		scrollPane = new JScrollPane(txtReceive, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
				JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		DefaultCaret caret = (DefaultCaret) txtReceive.getCaret();
		caret.setUpdatePolicy(DefaultCaret.ALWAYS_UPDATE);
		frame.add(txtReceive);

		JPanel panelSend = new JPanel();
		JTextField txtMessage = new JTextField();

		txtMessage.setColumns(40);
		txtMessage.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				broadcast(txtMessage.getText());
			}
		});

		JButton btnSend = new JButton("Send");
		btnSend.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				broadcast(txtMessage.getText());
			}
		});

		panelSend.add(txtMessage, BorderLayout.CENTER);
		panelSend.add(btnSend, BorderLayout.EAST);

		frame.add(panelSend, BorderLayout.SOUTH);

		frame.pack();
		frame.setVisible(true);

		while (clientName.equals("")) {
			clientName = JOptionPane.showInputDialog("Enter your name:");
		}
	}

	private void broadcast(String msgSend) {
		boolean bExit = false;

		Message msg = new Message();
		msg.mData = msgSend.getBytes();

		try {
			//send data to server
			mtxSendServer.acquire();
			if (!mIsFirst && !clientSocket.isClosed()) {
				sendTCPData(clientSocket, msg);
			}
			mtxSendServer.release();

			//send data to all clients
			mtxClient.acquire();
			for (Socket sock : arrClients) {
				sendTCPData(sock, msg);
			}
			mtxClient.release();

			if (msgSend.toLowerCase().equals("exit")) {
				bExit = true;
			}

			mtxClient.acquire();
			//close all clients
			for (Socket sock : arrClients) {
				sock.close();
			}
			//remove all clients from array
			arrClients.clear();
			mtxClient.release();

			//close socket if user "exits"
			mtxSendServer.acquire();
			clientSocket.close();
			mtxSendServer.release();

			serverSocket.close();
		}
		catch (Exception e) {
			writeMessage("[Network Error]");
		}
	}

	private void serverConnect() throws Exception {
		boolean bServerFound = false;

		while(!bServerFound && !mIsFirst)
		{
			try
			{
				mtxSendServer.acquire();
				clientSocket = new Socket(mIP, mClientPort);
				mtxSendServer.release();
				bServerFound = true;
			}
			catch(ConnectException e)
			{
				writeMessage("Server refused, retrying...");

				try {
					Thread.sleep(2000); //2 seconds
				}
				catch(InterruptedException ex) {
					//ex.printStackTrace();
				}
			}
		}
	}

	private void TCPConnection() throws Exception {
		//keep trying to connect to server


		//receive data from all clients
		serverSocket = new ServerSocket(mServerPort);
		Thread serverThread = new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					processConnections(serverSocket);
				}
				catch (Exception e) {
					//e.printStackTrace();
					writeMessage("[Server socket closed]");
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
						String msgReceive;

						boolean bExit = false;
						while (!bExit) {
							receiveTCPData(clientSocket, msg);
							msgReceive = new String(msg.mData);
							writeMessage("<client>: " + msgReceive);

							//send to all clients
							if (!msgReceive.toLowerCase().equals("exit")) {
								mtxClient.acquire();
								for (Socket sock : arrClients) {
									sendTCPData(sock, msg);
								}
								mtxClient.release();
							} else {
								bExit = true;
							}
						}
					} catch (Exception e) {
						//server stopped
						//e.printStackTrace();
						writeMessage("[Server disconnected]");
					}

					try {
						clientSocket.close();
						String host = JOptionPane.showInputDialog("Enter your name:");
						mClientPort = Integer.parseInt(host.substring(host.indexOf(':') + 1));
						mIP = host.substring(0, host.indexOf(':'));
						serverConnect();
					} catch (Exception e) {
						//e.printStackTrace();
					}
				}
			});
			listenThread.start();
		}
	}

	private void processConnections(ServerSocket serverSocket) throws Exception {
		//process requests (read/send data)
		while (true) {
			//wait for new connection
			Socket cSock = serverSocket.accept();
			writeMessage("[New connection]");

			mtxClient.acquire();
			arrClients.add(cSock);
			mtxClient.release();

			//create new thread to listen
			Thread thrConn = new Thread(new Runnable() {
				@Override
				public void run() {
					try {
						String recMsg;

						//keep reading data until user "exits"
						boolean bExit = false;
						while (!bExit) {
							//receive the data
							try {
								Message msg = new Message();
								receiveTCPData(cSock, msg);

								//send back capitalized text
								recMsg = new String(msg.mData);
								if (recMsg.toLowerCase().equals("exit")) {
									writeMessage("[client disconnecting]");
									msg.mData = "[A client disconnected]".getBytes();
									bExit = true;
								} else {
									writeMessage("<client>: " + recMsg);
								}

								//send data to all other clients
								mtxClient.acquire();
								for (Socket sock: arrClients) {
									if (!sock.equals(cSock)) {
										sendTCPData(sock, msg);
									}
								}
								mtxClient.release();

								//send data to server
								mtxSendServer.acquire();
								if (!mIsFirst && !clientSocket.isClosed()) {
									sendTCPData(clientSocket, msg);
								}
								mtxSendServer.release();
							}
							catch (Exception ex) {
								//client disconnected
								//ex.printStackTrace();
								writeMessage("[Client Socket closed]");
								bExit = true;
							}

							if (bExit) {
								mtxClient.acquire();
								arrClients.remove(clientSocket);
								mtxClient.release();
							}
						}
					}
					catch(Exception ex) {
						//ex.printStackTrace();
					}
				}
			});
			thrConn.start();	//start the thread
		}
	}

	//handles receiving TCP data and wraps the message in a Message class
	private void receiveTCPData(Socket socket, Message msg) throws Exception{
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
	private void sendTCPData(Socket socket, Message msg) throws Exception{
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

	private void writeMessage(String msg) {
		if (mIsFirst) {
			try {
				mtxTextArea.acquire();
				txtReceive.append(msg + "\n");
				scrollPane.scrollRectToVisible(txtReceive.getBounds());
			} catch (Exception e) {
				//e.printStackTrace();
			}
		} else {
			System.out.println(msg);
		}
	}
}