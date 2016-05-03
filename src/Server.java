import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;

import javax.xml.crypto.Data;

public class Server {

	@SuppressWarnings("resource")
	public static void main(String[] args) {
		try {
			// Setup
			int port = 6789;
			DatagramSocket datagramSocket = new DatagramSocket(port);
			byte[] buffer;

			System.out.println("Waiting for client...");

			// Receive client HS
			buffer = new byte[4];
			DatagramPacket clientHSReq = new DatagramPacket(buffer, buffer.length);
			datagramSocket.receive(clientHSReq);
			int clientHSReqVal = ByteBuffer.wrap(buffer).getInt();

			System.out.println("Received client HS - " + clientHSReqVal);

			// ACK
			DatagramPacket clientHSResp = new DatagramPacket(buffer, buffer.length, clientHSReq.getAddress(),
					clientHSReq.getPort());
			datagramSocket.send(clientHSResp);

			System.out.println("Sending client HS ACK");

			// Send server HS
			int serverHSReqVal = new Random().nextInt(256);
			buffer = ByteBuffer.allocate(4).putInt(serverHSReqVal).array();
			DatagramPacket serverHSReq = new DatagramPacket(buffer, buffer.length, clientHSReq.getAddress(),
					clientHSReq.getPort());
			datagramSocket.send(serverHSReq);

			System.out.println("Sending server HS - " + serverHSReqVal);

			// ACK
			DatagramPacket serverHSResp = new DatagramPacket(buffer, buffer.length);
			datagramSocket.receive(serverHSResp);
			int serverHSRespVal = ByteBuffer.wrap(buffer).getInt();

			if (serverHSReqVal == serverHSRespVal) {
				System.out.println("Received server HS ACK - " + serverHSRespVal);
			} else {
				throw new SocketException("Incorrect server HS ACK");
			}

			System.out.println("HS complete - waiting for client command");

			// Main loop
			while (true) {
				System.out.println("Waiting for client command");
				datagramSocket.setSoTimeout(0); // Infinite wait
				buffer = new byte[80];

				DatagramPacket clientReq = new DatagramPacket(buffer, buffer.length);
				datagramSocket.receive(clientReq);
				String request = new String(buffer).trim();

				if (request.equals("list")) {
					// ACK list
					buffer = new String("listACK").getBytes();
					DatagramPacket clientRespListACK = new DatagramPacket(buffer, buffer.length, clientReq.getAddress(),
							clientReq.getPort());
					datagramSocket.send(clientRespListACK);

					System.out.println("Sending client list ACK");

					// Get file list from server folder
					File folder = new File("server");
					String[] files = folder.list();
					String fileList = "";
					for (String s : files) {
						fileList += s + "\n";
					}

					// Send to client
					buffer = fileList.getBytes();
					DatagramPacket clientRespList = new DatagramPacket(buffer, buffer.length, clientReq.getAddress(),
							clientReq.getPort());
					datagramSocket.send(clientRespList);

					System.out.println("Sending client file list");
				}

				if (request.contains("get")) {
					String fileName = request.split("-")[1];

					System.out.println("Client requested: " + fileName);

					// Send file size
					File file = new File("server/" + fileName);
					long fileSizeL = file.length();
					buffer = ByteBuffer.allocate(Long.BYTES).putLong(fileSizeL).array();
					DatagramPacket fileSizeResp = new DatagramPacket(buffer, buffer.length, clientReq.getAddress(),
							clientReq.getPort());
					datagramSocket.send(fileSizeResp);

					System.out.println("Sending client file size - " + fileSizeL);

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
								DatagramPacket pkt = new DatagramPacket(numberedPacket, numberedPacket.length,
										clientReq.getAddress(), clientReq.getPort());
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
							System.out.println("Waiting for client ACK");

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

							// Don't move window forwards, re-send packets to
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

				if (request.contains("put")) {
					String fileName = request.split("-")[1];

					System.out.println("Client uploading " + fileName);

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
							if (random <= 10 && (readBytes + buffer.length) < fileSize) {
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
							 * DatagramPacket(pktNumber, pktNumber.length,
							 * packet.getAddress(), packet.getPort());
							 * datagramSocket.send(ack);
							 * 
							 * System.out.println("Sending ack "
							 * +(expectedPktNumb - 1));
							 */

							// 3 ACK dropped in a row
							if ((expectedPktNumb - numb) >= 3) {
								buffer = ByteBuffer.allocate(Integer.BYTES).putInt(expectedPktNumb - 1).array();

								DatagramPacket ack = new DatagramPacket(buffer, buffer.length, packet.getAddress(),
										packet.getPort());
								datagramSocket.send(ack);

								System.out.println("Sending ACK " + (expectedPktNumb - 1));
							}
						}

					}

					System.out.println("Received full file");

					System.out.println(fileBuffer[0] + "|" + fileBuffer[fileSize - 1]);

					FileOutputStream fos = new FileOutputStream(new File("server/" + fileName));
					fos.write(fileBuffer);
					fos.flush();

					fos.close();

				}

				if (request.equals("exit")) {
					// ACK exit
					buffer = new String("exitACK").getBytes();
					DatagramPacket clientRespExit = new DatagramPacket(buffer, buffer.length, clientReq.getAddress(),
							clientReq.getPort());
					datagramSocket.send(clientRespExit);

					System.out.println("Sending client exit ACK");

					break;
				}
			}

			System.out.println("Exiting server");

			if (datagramSocket != null) {
				datagramSocket.close();
			}

		} catch (

		SocketException e)

		{
			e.printStackTrace();
		} catch (

		IOException e)

		{
			e.printStackTrace();
		}

	}

}
