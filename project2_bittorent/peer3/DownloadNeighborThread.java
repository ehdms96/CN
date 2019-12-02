import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.Random;

public class DownloadNeighborThread implements Runnable {
	/* Connection Socket과 통신을 하기 위한 스트림과 소켓 */
	Socket connectionSocket;
	OutputStream outputStream;
	InputStream inputStream;
	DataInputStream input;
	DataOutputStream output;

	/* UploadNeighborThread가 해당 쓰레드에게 보내는 응답을 저장하는 변수이다 */
	String response;

	/* 현재 해당 쓰레드가 접근하려는 Peer의 Index (config.txt파일의 index를 따른다)
	 * 나 자신 = 0, 1~4 = 차례대로 피어1~4 */
	int peerIndex;
	int firstStartIndex; 	/* 처음에 응답을 요청할 peer Index */
	boolean alreadyConnected; /* 이미 친구목록에 있어 다른 쓰레드와 연결된 경우 */
	int[] randomChunk; /* Uploader에게 요청하기 위한 랜덤한 청크 인덱스를 저장하는 배열 */
	int randomChunklength; /* 랜덤한 청크 인덱스의 개수 */

	/* Type : 생성자
	 * 역할 : 해당 쓰레드가 몇번째 피어에게 연결을 요청할 것인가에 대한 정보를 초기화한다.
	 */
	public DownloadNeighborThread(int startIndex) {
		peerIndex = startIndex;
		firstStartIndex = startIndex;
	}

	/* Type : 입출력
	 * 역할 : 각종 스트림에서 데이터 입출력을 편하게 하기 위해서 미리 선언해둔 메서드이다.
	 * return : void  / int / String
	 */
	/* UTF를 UploadNeighborThread에게 전송하는 메서드 */
	void sendUTF(String Response) throws IOException {
		output.writeUTF(Response);
		output.flush();
	}

	/* INT를 UploadNeighborThread에게 전송하는 메서드 */
	void sendINT(int command) throws IOException {
		output.writeInt(command);
		output.flush();
	}

	/* Byte[]를 UploadNeighborThread에게 전송하는 메서드 */
	void sendBYTE(byte[] data) throws IOException {
		output.writeInt(data.length);
		output.write(data, 0, data.length);
		output.flush();
	}

	/* UploadNeighborThread로부터 UTF를 전송받는 메서드 */
	String receiveUTF() throws IOException {
		return input.readUTF();
	}

	/* UploadNeighborThread로부터 INT를 전송받는 메서드 */
	int receiveINT() throws IOException {
		return input.readInt();
	}

	/* UploadNeighborThread로부터 Byte[]를 전송받는 메서드 */
	void receiveBYTE(byte[] destination) throws IOException {
		int length = input.readInt();
		if (destination == null) {
			destination = new byte[length];
		}
		input.read(destination, 0, length);
	}

	/* Type : 입출력 
	 * 역할 : UploaderNeighborThread로부터 파일에 대한 정보를 전달받는 메서드
	 * 만약, 내가 파일 정보를 가지고 있지 않은 경우, 파일 정보를 초기화해준다.
	 * return : void
	 */
	void receiveInformation(int index) throws SocketTimeoutException, IOException {
		Torrent.friendsStatus[index] = receiveUTF();
		if (Torrent.friendsStatus[index].equals("CHUNK")) {
			Torrent.friendsChunkSize[index] = receiveINT();
			Torrent.friendsChunkNum[index] = receiveINT();
			Torrent.friendsChunkMap[index] = new byte[Torrent.friendsChunkNum[index]];
			receiveBYTE(Torrent.friendsChunkMap[index]);
			/* 파일 정보를 가지고 있지 않은 경우 */
			if (Torrent.thisChunkMap == null) {
				/* 초기화한다 */
				setInformation(Torrent.friendsChunkSize[index], Torrent.friendsChunkNum[index]);
			}
		}
	}

	/* Type : 입출력 
	 * 역할 : UploaderNeighborThread에게 자신이 가진 chunkMap을 전송하는 메서드.
	 * 만약, 내가 파일 정보를 가지고 있지 않은 경우 에러가 나므로 무조건 information 요청을 연결 처음에 해줘야함.
	 * return : void
	 */
	void sendChunkMap() throws IOException {
		sendUTF("CHUNKMAP");
		sendBYTE(Torrent.thisChunkMap);
		receiveUTF();
	}
	
	/* Type : 입출력 
	 * 역할 : UploaderNeighborThread에게 자신이 가지지 않은 청크중 랜덤한 청크 요청을 보내는 메서드.
	 * return : void
	 */
	void sendMissingChunkIndex() throws IOException {
		if (randomChunklength == 0)
			return;
		sendUTF("CHUNKINDEX");
		sendINT(randomChunklength);
		for (int i = 0; i < randomChunklength; i++) {
			sendINT(randomChunk[i]);
		}
		receiveUTF();
	}

	/* Type : 입출력 
	 * 역할 : UploaderNeighborThread로부터 요청한 청크에 대한 실제 byte[]데이터를 전송받는 메서드.
	 * sendMissingChunkIndex요청 이후에 사용해야 한다.
	 * return : void
	 */
	void receiveChunkData() throws IOException {
		if (randomChunklength == 0)
			return;
		sendUTF("CHUNKGET");
		for (int i = 0; i < randomChunklength; i++) {
			Torrent.thisChunkMap[randomChunk[i]] = (byte) 1;
			receiveBYTE(Torrent.thisFileData[randomChunk[i]]);
		}
	}

	/* Type : 체크 
	 * 역할 : 파라미터로 전달받은 청크가 가득찼는지 알려준다.
	 * return : boolean (true-> 가득찬 경우, false-> 하나라도 비어있는 경우)
	 */
	public boolean chunkFull(byte[] chunkMap) {

		if (chunkMap == null) {
			return false;
		}
		if (chunkMap.length == 0) {
			return false;
		}
		for (int i = 0; i < chunkMap.length; i++) {
			if (chunkMap[i] == (byte) 0)
				return false;
		}

		return true;
	}

	/* Type : 체크 
	 * 역할 : 파라미터로 전달받은 인덱스가 친구 리스트에 들어있는지 확인해준다..
	 * return : boolean (true-> 이미 친구목록에 있는 경우, false-> 친구목록에 없는 경우.)
	 */
	boolean existInFriendList(int index) {
		for (int i = 0; i < Torrent.friendsArr.length; i++) {
			if (index == Torrent.friendsArr[i]) {
				alreadyConnected = true;
				return true;
			}
		}
		return false;
	}

	/* Type : 체크 
	 * 역할 : 모든 피어들이 시더가 되었는지 확인하는 함수.
	 * return : boolean (true-> 모든 피어가 시더인 경우, false-> 시더가 아닌 피어가 있을 경우)
	 */
	boolean allPeerIsSeeder() {
		for (int i = 0; i < Torrent.MAX_PEER_NUM; i++) {
			if (Torrent.peerSeederList[i] == 0) {
				return false;
			}
		}
		return true;
	}

	/* Type : 계산 
	 * 역할 : 내가 가진 청크맵과 피어가 가진 청크맵중에서 없는 청크 번호를 찾아서, 그중 랜덤으로 인덱스를 뽑아낸다
	 * 피어가 가진 청크맵은 information 요청시 받아온다.
	 * 최대개수 : 3개 (0~3개) 
	 * return : void
	 */
	void findMissingChunkByRandom(int index) {
		/* missing chunk 찾기 */
		byte[] current = Torrent.thisChunkMap;
		int[] result = new int[Torrent.thisChunkNum];
		for (int k = 0; k < result.length; k++) {
			result[k] = -1;
		}

		int i = 0, j = 0;
		int count = 0;
		while (count < Torrent.thisChunkNum && i < current.length) {
			if ((current[i] == (byte) 0) && (Torrent.friendsChunkMap[index][i] == (byte) 1)) {
				count++;
				result[j] = i;
				j++;
			}
			i++;
		}
		System.out.println("missing chunk num = " + j);
		int total = j;

		/* missing chunk중 랜덤 선택 */
		randomChunk = new int[Torrent.MAX_SEND_CHUNK_NUM];

		for (i = 0; i < Torrent.MAX_SEND_CHUNK_NUM; i++) {
			randomChunk[i] = -1;
		}

		if (total > Torrent.MAX_SEND_CHUNK_NUM) {
			randomChunklength = Torrent.MAX_SEND_CHUNK_NUM;
		} else {
			randomChunklength = total;
		}

		Random r = new Random();
		for (i = 0; i < randomChunklength; i++) {
			randomChunk[i] = result[r.nextInt(total)];
			for (j = 0; j < i; j++) {
				if (randomChunk[i] == randomChunk[j]) {
					i--;
				}
			}
		}
	}

	/* Type : 쓰기
	 * 역할 : 전달받은 정보를 바탕으로 내피어의 파일 정보를 초기화한다.
	 * @synchronized (여러 쓰레드의 동시접근 불가)
	 * return : void
	 */
	synchronized void setInformation(int chunkSize, int chunkNum) {
		Torrent.thisChunkSize = chunkSize;
		Torrent.thisChunkNum = chunkNum;
		Torrent.thisChunkMap = new byte[chunkNum];
		Torrent.thisFileData = new byte[chunkNum][];
		for (int i = 0; i < chunkNum; i++) {
			Torrent.thisFileData[i] = new byte[chunkSize];
		}
	}

	/* Type : 쓰기
	 * 역할 : 현재 자신이 사용할 친구배열의 인덱스를 받아오고, 덮어쓴다.
	 * @synchronized (여러 쓰레드의 동시접근 불가)
	 * return : int (친구 배열의 index)
	 */
	synchronized int getFriendArrIndex() {
		Torrent.friendsIndex += 1;
		Torrent.friendsIndex = Torrent.friendsIndex % 3;
		return Torrent.friendsIndex;
	}

	/* Type : 쓰기
	 * 역할 : 현재 자신의 피어의 청크 리스트에 청크 데이터를 쓴다.
	 * @synchronized (여러 쓰레드의 동시접근 불가)
	 * return : void
	 */
	synchronized void writeData(int receivedChunknumber) throws IOException {
		Torrent.thisChunkMap[receivedChunknumber] = (byte) 1;
		inputStream.read(Torrent.thisFileData[receivedChunknumber]);
	}

	/* Type : 쓰레드 메인 
	 * 역할 : UploadNeighborThread에게 파일 다운로드에 필요한 정보를 요청하고, 정보들을 초기화하고
	 * 입출력을 통해 최종적으로 파일을 다운로드 받을 수 있도록 도와주는 쓰레드.
	 * 자신의 chunkMap이 가득찰 때까지
	 * 모든 피어가 seeder가 될때까지 다른 피어에게 접근해 정보를 주고받는다.
	 * UploadNeighborThread와 연결되면, friend관계를 맺고 파일 정보를 계속해서 주고받다가
	 * 오랜시간 청크 다운로드가 이루어지지 않거나, 연결 요청에 실패한 경우
	 * 다음 피어를 찾아 친구 요청을 한다.
	 * return : void
	 */
	@Override
	public void run() {
		/* 청크가 가득 찰 때까지 수행 */
		while (!chunkFull(Torrent.thisChunkMap)) {
			try {
				/* config.txt파일의 정보를 바탕으로 연결 요청을 한다 */
				System.out.println("<DownloadNeighborThread> start chunkdownload");
				SocketAddress socketAddress = new InetSocketAddress(Torrent.peerAddressArr[peerIndex],
						Torrent.peerPortNumArr[peerIndex]);
				connectionSocket = new Socket();
				connectionSocket.setSoTimeout(Torrent.DOWNLOAD_RESPONSE_TIMEOUT);
				connectionSocket.connect(socketAddress, Torrent.SOCKET_CONNECT_TIMEOUT);
				inputStream = connectionSocket.getInputStream();
				outputStream = connectionSocket.getOutputStream();
				input = new DataInputStream(inputStream);
				output = new DataOutputStream(outputStream);
				System.out.println("<DownloadNeighborThread> connected");
				System.out.println("<DownloadNeighborThread> " + Torrent.peerAddressArr[peerIndex] + " : "
						+ Torrent.peerPortNumArr[peerIndex]);

				/* 다른 쓰레드를 통해서 이미 친구관계를 맺고 연결이 되어있는지 검사 */
				alreadyConnected = false;
				if (existInFriendList(peerIndex)) {
					System.out.println("<DownloadNeighborThread> already in friendlist");
					sendUTF("FRIEND");
				}

				/* 이미 연결이 되어있지 않으면, 즉 친구관계를 맺고 있는 피어가 아닌경우. */
				if (!alreadyConnected) {
					/* 친구목록에 추가하기 위해서 인덱스를 받아옴 */
					int friendArrIndex = getFriendArrIndex();
					Torrent.friendsArr[friendArrIndex] = peerIndex;
					/* 5초간 타임아웃일 경우, 청크맵을 재전송하고, 10초간 연결이 없는경우 연결을 해제한다 */
					boolean isTimeOut = false;
					/* 청크 다운로드 과정 수행 메인 로직.
					 * 1. INFORMATION 요청
					 * 2-1 만약 해당 피어가 파일 정보가 있고, 청크를 가지고 있는 경우
					 * 		3. 전달받은 정보를 바탕으로 미싱청크 찾기
					 * 		4-1. 만약 내 청크맵이 가득차지 않은 경우
					 * 			5. 랜덤 MissingChunkIndex를 전송함 (최대 3개)
					 * 			6. 해당 인덱스에 해당하는 청크 데이터를 전송받음 (최대 3개)
					 * 			 -> 1로 돌아감.
					 * 		4-2. 만약 내 청크맵이 가득찬 경우 (다운받을 필요 없음)
					 *			5. 연결 종료. 
					 * 2-2 만약 해당 피어가 파일 정보가 없는 경우
					 *  	3. 연결 종료. */
					while (true) {
						try {
							Thread.sleep(Torrent.SLEEP_TIME);
							sendUTF("INFORMATION");
							receiveInformation(peerIndex);

							if (Torrent.friendsStatus[peerIndex].equals("CHUNK")) {
								System.out.println("<DownloadNeighborThread> server have chunk. download start");
								findMissingChunkByRandom(peerIndex);
								sendChunkMap();
								if (chunkFull(Torrent.thisChunkMap)) {
									System.out.println("<DownloadNeighborThread> chunk is full.");
									break;
								}

								sendMissingChunkIndex();
								receiveChunkData();
								/* 요청을 받은경우, timeout 초기화 */
								isTimeOut = false;
							} else if (Torrent.friendsStatus[peerIndex].equals("NOCHUNK")) {
								break;
							}
						} catch (SocketTimeoutException e) {
							/* 10초간 응답이 없는경우 -> 해당 피어와 연결 종료, 다른 피어찾기. */
							if (isTimeOut == true) {
								System.out.println("<DownloadNeighborThread> timeout. find other connection");
								break;
							}
							/* 5초간 응답이 없는경우, Timeout이 true가 됨. */
							isTimeOut = true;
							System.out.println("<DownloadNeighborThread> timeout. resend bitmap");
						} catch (IOException e) {
							e.printStackTrace();
							System.out.println("<DownloadNeighborThread> error.");
						}
					}
					/* 해당 인덱스와 친구연결 해제*/
					Torrent.friendsArr[friendArrIndex] = -1;
					
					inputStream.close();
					outputStream.close();
					input.close();
					output.close();
				}
				/* 연결 요청에 실패할 경우 (Timeout = 5초) */
			} catch (ConnectException e) {
				System.out.println("<DownloadNeighborThread> No answer in " + Torrent.peerAddressArr[peerIndex] + " : "
						+ Torrent.peerPortNumArr[peerIndex] + " find other connection");
			} catch (Exception e) {
				System.out.println("<DownloadNeighborThread> connection fail");
			} finally {
				try {
					Thread.sleep(Torrent.SLEEP_TIME);
					System.out.println("<DownloadNeighborThread> connection end");
					connectionSocket.close();
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
			/* 인덱스 증가 (1,2,3,4 반복) */
			peerIndex = ((peerIndex) % (Torrent.MAX_PEER_NUM - 1)) + 1;
		}
		/* 다운로드 종료 : 현재 피어를 시더로 설정 -> 해당 작업 진행시 Torrent Thread에서 파일을 만들고 종료. */
		Torrent.peerSeederList[0] = 1;
		System.out.println("<DownloadNeighborThread> download end");
		peerIndex = firstStartIndex;
		/* 서버를 종료해도 되는지 여부를 검사하기 위해 (아직 청크 받고있는 녀석들이 있을수도 있으므로)
		 * 다른 피어들이 모두 시더인지 검사하는 로직. 꼭 필요하지는 않으나 자연스럽게 서버스레드까지 종료시키기 위해 수행
		 */
		while (!allPeerIsSeeder()) {
			try {
				/* 연결 요청 */
				System.out.println("<DownloadNeighborThread> start seedercheck");
				SocketAddress socketAddress = new InetSocketAddress(Torrent.peerAddressArr[peerIndex],
						Torrent.peerPortNumArr[peerIndex]);
				connectionSocket = new Socket();
				connectionSocket.setSoTimeout(Torrent.DOWNLOAD_RESPONSE_TIMEOUT);
				connectionSocket.connect(socketAddress, Torrent.SOCKET_CONNECT_TIMEOUT);
				inputStream = connectionSocket.getInputStream();
				outputStream = connectionSocket.getOutputStream();
				input = new DataInputStream(inputStream);
				output = new DataOutputStream(outputStream);
				System.out.println("<DownloadNeighborThread> connected");
				System.out.println("<DownloadNeighborThread> " + Torrent.peerAddressArr[peerIndex] + " : "
						+ Torrent.peerPortNumArr[peerIndex]);
				
				/* SEEDER 요청을 통해 시더인지 물어보기 */
				sendUTF("SEEDER");
				/* 해당 피어의 시더여부를 배열에 저장, (시더 = 1, else = 0) */
				Torrent.peerSeederList[peerIndex] = receiveINT();
				
				
				for (int i = 0; i < Torrent.peerSeederList.length; i++) {
					System.out.print(Torrent.peerSeederList[i] + " ");
				}
				System.out.println();
				Thread.sleep(Torrent.SLEEP_TIME);
				
				inputStream.close();
				outputStream.close();
				input.close();
				output.close();
			} catch (SocketException e) {
				System.out.println("<DownloadNeighborThread> No answer in " + Torrent.peerAddressArr[peerIndex] + " : "
						+ Torrent.peerPortNumArr[peerIndex] + " find other connection");
			} catch (Exception e) {
				System.out.println("<DownloadNeighborThread> connection fail");
				e.printStackTrace();
			} finally {
				try {
					/* 연결 종료 */
					Thread.sleep(Torrent.SLEEP_TIME);
					System.out.println("<DownloadNeighborThread> connection end");
					connectionSocket.close();
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
			/* 피어 인덱스 증가 */
			peerIndex = ((peerIndex) % (Torrent.MAX_PEER_NUM - 1)) + 1;
		}
		/* 해당 반복문을 빠져나온 경우, 모든 피어가 시더이므로 해당 변수를 true로 설정해 server가 꺼질수 있게 해준다*/
		Torrent.allPeerIsSeeder = true;
		System.out.println("<DownloadNeighborThread> thread end");
	}
}
