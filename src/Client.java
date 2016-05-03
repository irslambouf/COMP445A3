import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;
import java.util.Scanner;

public class Client {

	@SuppressWarnings("resource")
	public static void main(String[] args) {
		try {
			// Setup
			DatagramSocket datagramSocket = new DatagramSocket();
			InetAddress host = InetAddress.getByName("localhost");
			int port = 6789;
			byte[] buffer;
			ArrayList<String> fileList = new ArrayList<String>();

			// Client HS
			int clientHSVal = new Random().nextInt(256);
			buffer = ByteBuffer.allocate(4).putInt(clientHSVal).array();
			DatagramPacket clientHSReq = new DatagramPacket(buffer, buffer.length, host, port);
			datagramSocket.send(clientHSReq);

			System.out.println("Sending client HS - " + clientHSVal);

			// ACK
			DatagramPacket clientHSResp = new DatagramPacket(buffer, buffer.length);
			datagramSocket.receive(clientHSResp);
			int clientHSRespVal = ByteBuffer.wrap(buffer).getInt();

			// Check ACK is same as sent value
			if (clientHSVal == clientHSRespVal) {
				System.out.println("Received client HS ACK - " + clientHSRespVal);
			} else {
				throw new SocketException("Incorrect client HS ACK");
			}

			// Receive server HS
			DatagramPacket serverHSReq = new DatagramPacket(buffer, buffer.length);
			datagramSocket.receive(serverHSReq);
			int serverHSReqVal = ByteBuffer.wrap(buffer).getInt();

			System.out.println("Received server HS - " + serverHSReqVal);

			// ACK
			DatagramPacket serverHSResp = new DatagramPacket(buffer, buffer.length, serverHSReq.getAddress(),
					serverHSReq.getPort());
			datagramSocket.send(serverHSResp);

			System.out.println("HS complete");

			// Main loop
			Scanner keyboard = new Scanner(System.in);
			while (true) {
				System.out.println("List of available commands: ");
				System.out.println("1. List file(s)");
				System.out.println("2. Download file");
				System.out.println("3. Upload file");
				System.out.println("4. Exit");
				System.out.print("Choice: ");

				int choice = keyboard.nextInt();

				if (choice > 0 && choice <= 4) {

					// List files
					if (choice == 1) {
						buffer = new String("list").getBytes();
						DatagramPacket clientReqList = new DatagramPacket(buffer, buffer.length, host, port);
						datagramSocket.send(clientReqList);

						System.out.println("Request server file list");

						// ACK
						buffer = new byte[500]; // Long enough for file list
						datagramSocket.setSoTimeout(500); // 500 ms timeout
						DatagramPacket clientRespListACK = new DatagramPacket(buffer, buffer.length);
						while (true) {
							try {
								datagramSocket.receive(clientRespListACK);
								String serverACK = new String(buffer).trim();

								if (serverACK.equals("listACK")) {
									System.out.println("Server ACK list");

									// Get file list
									datagramSocket.setSoTimeout(0);
									DatagramPacket clientRespList = new DatagramPacket(buffer, buffer.length);
									datagramSocket.receive(clientRespList);
									String files = new String(buffer).trim();

									for (String s : files.split("\n")) {
										fileList.add(s);
									}

									System.out.println("Server file list downloaded");

									break;
								} else {
									throw new SocketException("Invalid server ACK");
								}
							} catch (SocketTimeoutException e) {
								System.out.println("Server didn't ACK -> resend");

								datagramSocket.send(clientReqList);
								continue;
							}
						}
					}

					// Download file
					if (choice == 2) {
						if (!fileList.isEmpty()) {
							// Ask what file to download
							int downloadChoice = 0;
							while (true) {
								System.out.println("Files available for download");
								for (int i = 0; i < fileList.size(); i++) {
									System.out.println(i + ". " + fileList.get(i));
								}
								System.out.print("Choice: ");

								downloadChoice = keyboard.nextInt();

								if (downloadChoice >= 0 && downloadChoice < fileList.size()) {
									break;
								} else {
									System.out.println("Invalid choice, try again");
								}
							}

							// Send server request
							buffer = new String("get-" + fileList.get(downloadChoice)).getBytes();
							DatagramPacket clientReqFile = new DatagramPacket(buffer, buffer.length, host, port);
							datagramSocket.send(clientReqFile);

							System.out.println("Sending server file request - " + fileList.get(downloadChoice));

							// Wait for file size
							buffer = new byte[Long.BYTES];
							datagramSocket.setSoTimeout(0);
							DatagramPacket fileSizeResp = new DatagramPacket(buffer, buffer.length);
							datagramSocket.receive(fileSizeResp);
							int fileSize = (int) ByteBuffer.wrap(buffer).getLong();
							byte[] fileBuffer = new byte[fileSize];

							System.out.println("File size of " + fileSize);

							int readBytes = 0;
							int expectedPktNumb = 0;

							while (readBytes < fileSize) {
								buffer = new byte[84];

								System.out.println("waiting for next packet -> " + expectedPktNumb);

								DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
								datagramSocket.receive(packet);

								// Last packet
								if (packet.getLength() < 84) {
									buffer = Arrays.copyOf(buffer, packet.getLength());
								}

								// Get packet number
								byte[] pktNumber = Arrays.copyOfRange(buffer, 0, 4);
								int numb = ByteBuffer.wrap(pktNumber).getInt();

								// Trim buffer of sequence number
								buffer = Arrays.copyOfRange(buffer, 4, buffer.length);

								System.out.println("Received packet (" + buffer.length + " byte) " + numb);

								if (expectedPktNumb == numb) {

									int random = new Random().nextInt(101); // [0-100]

									// ACK 
									if (random <= 10 && (readBytes + buffer.length) < fileSize ) {
										System.out.println("ACK dropped");
									} else {
										DatagramPacket ack = new DatagramPacket(pktNumber, pktNumber.length,
												packet.getAddress(), packet.getPort());
										datagramSocket.send(ack);

										System.out.println("Sending ack " + numb);
									}

									System.arraycopy(buffer, 0, fileBuffer, readBytes, buffer.length);

									readBytes += buffer.length;
									expectedPktNumb++;

									System.out.println("Read " + readBytes + "/" + fileSize);
								} else {
									System.out.println("Out of order packet -> discard");

									// Don't send cumulative ACK for timeout to
									// work
									/*
									 * pktNumber =
									 * ByteBuffer.allocate(Integer.BYTES).putInt
									 * (expectedPktNumb - 1).array();
									 * 
									 * DatagramPacket ack = new
									 * DatagramPacket(pktNumber,
									 * pktNumber.length, packet.getAddress(),
									 * packet.getPort());
									 * datagramSocket.send(ack);
									 * 
									 * System.out.println("Sending ack "
									 * +(expectedPktNumb - 1));
									 */
									
									// 3 dropped ack in a row
									if ((expectedPktNumb - numb) >= 3){
										buffer = ByteBuffer.allocate(Integer.BYTES).putInt(expectedPktNumb - 1).array();
										
										DatagramPacket ack = new DatagramPacket(buffer, buffer.length,packet.getAddress() , packet.getPort());
										datagramSocket.send(ack);
										
										System.out.println("Sending ACK "+(expectedPktNumb - 1));
									}
								}

							}
							
							
							System.out.println("Received full file");

							System.out.println(fileBuffer[0] + "|" + fileBuffer[fileSize - 1]);

							FileOutputStream fos = new FileOutputStream(
									new File("client/" + fileList.get(downloadChoice)));
							fos.write(fileBuffer);
							fos.flush();

							fos.close();

						} else {
							System.out.println("Download server file list first then retry");
						}
					}
					
					// Upload file
					if (choice == 3) {

						// Get file list
						File folder = new File("client");
						String[] fileListLocal = folder.list();
						int clientDLChoice;

						// Show files for upload
						while (true) {
							System.out.println("Files available for upload:");
							for (int i = 1; i <= fileListLocal.length; i++) {
								System.out.println(i + ". " + fileListLocal[i - 1]);
							}
							System.out.print("Choice: ");

							clientDLChoice = keyboard.nextInt();

							if (clientDLChoice > 0 && clientDLChoice <= fileListLocal.length) {
								break;
							} else {
								System.out.println("Invalid choice, try again");
							}
						}

						// Send request
						buffer = new String("put-" + fileListLocal[clientDLChoice - 1]).getBytes();
						DatagramPacket clientReqFile = new DatagramPacket(buffer, buffer.length, host, port);
						datagramSocket.send(clientReqFile);

						// Send fileSize
						File file = new File("client/" + fileListLocal[clientDLChoice - 1]);
						long fileSizeL = file.length();
						buffer = ByteBuffer.allocate(Long.BYTES).putLong(fileSizeL).array();
						DatagramPacket fileSizeResp = new DatagramPacket(buffer, buffer.length, host, port);
						datagramSocket.send(fileSizeResp);

						System.out.println("Sending server file size - " + fileSizeL);

						// Setup transfer loop
						FileInputStream fis = new FileInputStream(file);
						byte[] fileBytes = new byte[(int) fileSizeL];
						fis.read(fileBytes);
						fis.close();
						int sendBase = 0;
						int windowIndex = 0;
						final int WINDOW_SIZE = 3;
						int waitingForACK = 0;
						boolean lastPacket = false;
						boolean lastACK = false;

						// Global loop
						while (true) {
							// Sending loop
							while (true) {

								// Get Data
								if (((int) fileSizeL) < 80) {
									// Small file
									buffer = Arrays.copyOfRange(fileBytes, 0, ((int) fileSizeL));
									lastPacket = true;
								} else {
									if ((sendBase + (windowIndex * 80) + 80) > ((int) fileSizeL)) {
										if (lastPacket) {
											if (sendBase + (windowIndex * 80) < ((int) fileSizeL)) {
												// Last packet already sent
												buffer = Arrays.copyOfRange(fileBytes, sendBase + (windowIndex * 80),
														((int) fileSizeL));
											} else {
												break;
											}

										} else {
											// Last packet
											buffer = Arrays.copyOfRange(fileBytes, sendBase + (windowIndex * 80),
													((int) fileSizeL));
											lastPacket = true;
										}

									} else {
										// Normal case
										buffer = Arrays.copyOfRange(fileBytes, sendBase + (windowIndex * 80),
												sendBase + (windowIndex * 80) + 80);
									}

								}

								// Calc packet number
								int number = (sendBase / 80) + windowIndex;

								// Add packet number
								byte[] numberedPacket = new byte[4 + buffer.length];
								byte[] pktNum = ByteBuffer.allocate(4).putInt(number).array();
								System.arraycopy(pktNum, 0, numberedPacket, 0, pktNum.length);
								System.arraycopy(buffer, 0, numberedPacket, pktNum.length, buffer.length);

								int random = new Random().nextInt(101); // [0-100]

								if (random <= 10) {
									System.out.println("Packet dropped " + number);
								} else {
									// Send packet
									DatagramPacket pkt = new DatagramPacket(numberedPacket, numberedPacket.length, host,
											port);
									datagramSocket.send(pkt);

									System.out.println("Sending packet " + number);
								}

								// Check if window full or last packet
								if (++windowIndex == WINDOW_SIZE || lastPacket) {
									waitingForACK = (sendBase / 80) + windowIndex - 1;
									break;
								}
							}

							buffer = new byte[Integer.BYTES];
							datagramSocket.setSoTimeout(500);

							try {
								System.out.println("Waiting for server ACK "+waitingForACK);

								DatagramPacket ack = new DatagramPacket(buffer, buffer.length);
								datagramSocket.receive(ack);

								int ackNumb = ByteBuffer.wrap(buffer).getInt();

								// Received move window forwards
								sendBase += ((ackNumb + 1) - (sendBase / 80)) * 80;

								windowIndex = (waitingForACK - ackNumb);

								System.out.println("Received " + ackNumb + ", advancing base to " + sendBase);

								if (ackNumb == ((int) fileSizeL / 80)) {
									lastACK = true;
								}
							} catch (SocketTimeoutException e) {

								// Don't move window forwards, re-send packets
								// to
								// fill window
								windowIndex = 0;

								System.out.println("Timeout - resend from " + sendBase);
							}

							System.out.println("Sent " + sendBase + "/" + fileSizeL);

							if (lastPacket && lastACK) {
								break;
							}
						}
						
						System.out.println("Finished sending file");

						System.out.println(fileBytes[0] + "|" + fileBytes[(int) fileSizeL - 1]);
					}

					// Exit
					if (choice == 4) {
						// Send request
						buffer = new String("exit").getBytes();
						DatagramPacket clientReqExit = new DatagramPacket(buffer, buffer.length, host, port);
						datagramSocket.send(clientReqExit);

						// Receive ACK
						buffer = new byte[500]; // Long enough
						datagramSocket.setSoTimeout(500); // 500ms timeout
						DatagramPacket clientRespExit = new DatagramPacket(buffer, buffer.length);
						while (true) {
							try {
								datagramSocket.receive(clientRespExit);
								String ackExit = new String(buffer).trim();

								if (ackExit.equals("exitACK")) {
									System.out.println("Server ack -> exitACK");
									break;
								} else {
									throw new SocketException("Invalid server ack");
								}

							} catch (SocketTimeoutException e) {
								System.out.println("Server didn't ACK -> resend exit");

								datagramSocket.send(clientReqExit);
								continue;
							}
						}

						break;
					}

				} else {
					System.out.println("Invalid choice, please try again");
				}
			}

			System.out.println("Exiting client");

			if (datagramSocket != null) {
				datagramSocket.close();
			}

		} catch (SocketException e) {
			e.printStackTrace();
		} catch (UnknownHostException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}

	}

}
