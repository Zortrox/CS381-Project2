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
import java.util.concurrent.atomic.AtomicBoolean;

public class ChatProgram {

	//TCP data wrapper
	private class Message {
		byte[] mData;		//actual data
		byte[] mName;		//original sender's name
	}

	private int mClientPort = 4578;
	private int mServerPort = 4513;
	private String mIP = "127.0.0.1";
	private boolean mIsFirst = false;

	private Socket clientSocket;
	private ServerSocket serverSocket;

	private String clientName = "";
	private JTextArea txtReceive;
	private JScrollPane scrollPane;
	private JTextField txtMessage;

	private Semaphore mtxClient = new Semaphore(1);
	private Semaphore mtxSendServer = new Semaphore(1);
	private Semaphore mtxTextArea = new Semaphore(1);
	private AtomicBoolean bConnected = new AtomicBoolean(false);

	private ArrayList<Socket> arrClients = new ArrayList<>();

	public static void main(String[] args) {
		ChatProgram client = new ChatProgram();

		try {
			client.TCPConnection();
		} catch (Exception ex) {
			System.out.println("[Network Error Occurred]");
		}
	}

	private ChatProgram() {
		JFrame frame = new JFrame("Chat Client");
		frame.setSize(600, 400);
		frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);

		txtReceive = new JTextArea(1, 20);
		txtReceive.setEditable(false);
		scrollPane = new JScrollPane(txtReceive, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
				JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		DefaultCaret caret = (DefaultCaret) txtReceive.getCaret();
		caret.setUpdatePolicy(DefaultCaret.ALWAYS_UPDATE);
		frame.add(scrollPane);

		JPanel panelSend = new JPanel();
		txtMessage = new JTextField();
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

		frame.setVisible(true);

		while (clientName == null || clientName.equals("")) {
			clientName = JOptionPane.showInputDialog("Enter your name:");
		}
	}

	private void broadcast(String msgSend) {
		Message msg = new Message();
		msg.mName = clientName.getBytes();
		msg.mData = msgSend.getBytes();
		writeMessage("<" + clientName + ">: " + msgSend);
		txtMessage.setText("");

		try {
			//send data to server
			mtxSendServer.acquire();
			if (!mIsFirst && bConnected.get()) {
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
		} catch (Exception e) {
			writeMessage("[Network Error]");
		}
	}

	private void serverConnect() throws Exception {
		String host = JOptionPane.showInputDialog("Connect to IP:Port", mIP + ":" + mClientPort);
		if (host == null || host.equals("")) {
			mIsFirst = true;
		} else {
			try {
				mClientPort = Integer.parseInt(host.substring(host.indexOf(':') + 1));
				mIP = host.substring(0, host.indexOf(':'));
			} catch (Exception e) {
				mIsFirst = true;
			}
		}

		if (serverSocket == null) {
			JOptionPane.showInputDialog("Listen for connections on", mServerPort);
		}

		while(!bConnected.get() && !mIsFirst)
		{
			try
			{
				mtxSendServer.acquire();
				clientSocket = new Socket(mIP, mClientPort);
				mtxSendServer.release();
				bConnected.set(true);
			}
			catch(ConnectException e)
			{
				//release the mutex
				mtxSendServer.release();

				writeMessage("Server refused, retrying...");

				try {
					Thread.sleep(2000); //2 seconds
				}
				catch(InterruptedException ex) {
					//ex.printStackTrace();
				}
			}
		}

		if (!mIsFirst) {
			writeMessage("[Connected to " + mIP + ":" + mClientPort + "]");
		} else {
			writeMessage("[Setting as main server]");
		}
	}

	private void TCPConnection() throws Exception {
		writeMessage("Starting Client in TCP\n");

		//keep trying to connect to server
		serverConnect();

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
						bConnected.set(false);
					}

					try {
						clientSocket.close();
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

								//exit on client sending "exit"
								recMsg = new String(msg.mData);
								if (recMsg.toLowerCase().equals("exit")) {
									String strDis = "[" + new String(msg.mName) + " disconnected]";
									writeMessage(strDis);
									msg.mData = strDis.getBytes();
									bExit = true;
								} else {
									writeMessage("<" + new String(msg.mName) + ">: " + recMsg);
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

		//get size of receiving name
		byte[] byteSize = new byte[4];
		inData.readFully(byteSize);
		ByteBuffer byteName = ByteBuffer.wrap(byteSize);
		int nameSize = byteName.getInt();
		//receive data
		msg.mName = new byte[nameSize];
		inData.readFully(msg.mName);

		//get size of receiving data
		byteSize = new byte[4];
		inData.readFully(byteSize);
		ByteBuffer byteData = ByteBuffer.wrap(byteSize);
		int dataSize = byteData.getInt();
		//receive data
		msg.mData = new byte[dataSize];
		inData.readFully(msg.mData);
	}

	//handles sending wrapped data in the Message class over TCP
	private void sendTCPData(Socket socket, Message msg) throws Exception{
		DataOutputStream outData = new DataOutputStream(socket.getOutputStream());

		//wrap name
		ByteBuffer byteName = ByteBuffer.allocate(4);
		byteName.putInt(msg.mName.length);
		byte[] nameSize = byteName.array();
		outData.write(nameSize);
		//send name
		outData.write(msg.mName);

		//wrap size of data
		ByteBuffer byteData = ByteBuffer.allocate(4);
		byteData.putInt(msg.mData.length);
		byte[] dataSize = byteData.array();
		outData.write(dataSize);
		//send data
		outData.write(msg.mData);

		//flush the stream
		outData.flush();
	}

	private void writeMessage(String msg) {
		try {
			mtxTextArea.acquire();
			txtReceive.append(msg + "\n");
			scrollPane.scrollRectToVisible(txtReceive.getBounds());
			mtxTextArea.release();
		} catch (Exception e) {
			//e.printStackTrace();
		}
	}
}