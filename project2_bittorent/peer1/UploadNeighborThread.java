import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

public class UploadNeighborThread implements Runnable {
	/* Connection Socket과 통신을 하기 위한 스트림과 소켓 */
	Socket connectionSocket;
	OutputStream outputStream;
	InputStream inputStream;
	DataOutputStream output;
	DataInputStream input;

	/* DownloadNeighborThread가 해당 쓰레드에게 보내는 요청을 저장하는 변수이다 */
	String request; 

	/* DownloadNeighborThread가 보내는 청크맵 배열을 저장 */
	byte[] receivedChunkMap;
	
	/* DownloadNeighborThread가 요청하는 청크에 대한 index가 저장되는 배열 */
	int[] requestChunkIndexList;

	/* Type : 생성자
	 * 역할 : 해당 connectionSocket과의 입출력 처리를 위해
	 * 미리 데이터 스트림을 열어둔다.
	 */
	UploadNeighborThread(Socket connectionSocket) throws IOException {
		this.connectionSocket = connectionSocket;
		this.outputStream = connectionSocket.getOutputStream();
		this.inputStream = connectionSocket.getInputStream();
		this.output = new DataOutputStream(this.outputStream);
		this.input = new DataInputStream(this.inputStream);
	}

	/* Type : 입출력 
	 * 역할 : 각종 스트림에서 데이터 입출력을 편하게 하기 위해서 미리 선언해둔 메서드이다.
	 * return : void  / String
	 */
	// UTF를 DownloadNeighborThread에게 전송하는 메서드
	void sendUTF(String Response) throws IOException {
		output.writeUTF(Response);
		output.flush();
	}

	// INT를 DownloadNeighborThread에게 전송하는 메서드
	void sendINT(int command) throws IOException {
		output.writeInt(command);
		output.flush();
	}

	// BYTE를 DownloadNeighborThread에게 전송하는 메서드
	void sendBYTE(byte[] data) throws IOException {
		output.writeInt(data.length);
		output.write(data, 0, data.length);
		output.flush();
	}

	// UTF를 DownloadNeighborThread으로부터 받는 메서드 
	String receiveUTF() throws IOException {
		return input.readUTF();
	}

	// INT를 DownloadNeighborThread으로부터 받는 메서드 
	int receiveINT() throws IOException {
		return input.readInt();
	}

	// BYTE를 DownloadNeighborThread으로부터 받는 메서드 
	void receiveBYTE(byte[] destination) throws IOException {
		int length = input.readInt();
		if (destination == null) {
			destination = new byte[length];
		}
		input.read(destination, 0, length);
	}
	
	/* Type : 체크 
	 * 역할 : DownloadThread가 보낸 chunkmap이 가득찼는지 검사하는 메서드이다.
	 * return : true -> if full, false -> if not full
	 */
	// Check that chunk ID list is full
	public boolean receivedChunkFull() {
		if (this.receivedChunkMap == null) {
			return false;
		}
		if (this.receivedChunkMap.length == 0) {
			return false;
		}
		for (int i = 0; i < this.receivedChunkMap.length; i++) {
			if (this.receivedChunkMap[i] == (byte) 0)
				return false;
		}
		return true;
	}

	/* Type : 쓰레드 메인 
	 * 역할 : DownloadNeighborThread의 요청에 대해서 데이터 입출력을 담당하는 쓰레드이다.
	 * 해당 DownloadNeighborThread가 청크가 가득찼다고 요청을 하거나,
	 * 연결을 종료해야만 하는 Request를 요청할때까지 계속 친구관계를 유지한다.
	 */
	@Override
	public void run() {
		try {
			System.out.println("<UploadNeighborThread> start");
			/* 종료조건1. 다운로더의 청크가 가득찬 경우. */
			while (!receivedChunkFull()) {
				/* 해당 다운로더의 요청을 계속해서 받아들임. */
				request = receiveUTF();
				System.out.println("<UploadNeighborThread> request : " + request);
					/* CHUNKINDEX 요청
					 * DownloadNeighborThread -> UploadNeighborThread
					 * 다운로드하기 위한 청크의 인덱스를 UploadNeighborThread에게 전송한다.
					 * 해당 요청은 requestChunkIndexList[]에 저장된다. */
				if (request.equals("CHUNKINDEX")) {
					int chunkNum = receiveINT();
					requestChunkIndexList = new int[chunkNum];
					for (int i = 0; i < chunkNum; i++) {
						requestChunkIndexList[i] = receiveINT();
					}
					sendUTF("CHUNKINDEX");
					/* CHUNKGET 요청
					 * UploadNeighborThread -> DownloadNeighborThread
					 * 전송된 인덱스 번호에 맞는 청크를 전송해준다. */
				} else if (request.equals("CHUNKGET")) {
					for (int i = 0; i < requestChunkIndexList.length; i++) {
						sendBYTE(Torrent.thisFileData[requestChunkIndexList[i]]);
					}
					/* CHUNKMAP 요청
					 * DownloadNeighborThread -> UploadNeighborThread
					 * 현재 자신의 Chunkmap을 uploadNeighborThread에게 보낼때 사용. */
				} else if (request.equals("CHUNKMAP")) {
					receiveBYTE(receivedChunkMap);
					/* 종료조건2. CHUNKMAP을 받았는데, 다운로더의 청크가 가득찬 경우. */
					if (receivedChunkFull()) {
						break;
					}
					sendUTF("CHUNKMAP");
					/* INFORMATION 요청
					 * UploadNeighborThread -> DownloadNeighborThread
					 * 시더에게 해당 파일에 대한 파일정보를 요청*/
				} else if (request.equals("INFORMATION")) {
					if (Torrent.thisChunkMap == null) {
						sendUTF("NOCHUNK");
						/* 종료조건3. 해당 UploadNeighborThread가 파일에 대한 정보가 없는 경우 */
						break;
					} else {
						sendUTF("CHUNK");
						sendINT(Torrent.thisChunkSize);
						sendINT(Torrent.thisChunkNum);
						sendBYTE(Torrent.thisChunkMap);
					}
					/* FRIEND 요청
					 * UploadNeighborThread -> DownloadNeighborThread
					 * 시더에게 해당 파일에 대한 파일정보를 요청*/
				} else if (request.equals("FRIEND")) {
					/* 종료조건4. 이미 해당 포트의 서버와 친구관계를 맺고 통신중. */
					break;
					/* SEEDER 요청
					 * UploadNeighborThread -> DownloadNeighborThread
					 * 해당 요청이 들어오면, 자신이 시더인지 아닌지 여부를 알려줌.*/
				} else if (request.equals("SEEDER")) {
					if (Torrent.peerSeederList[0]==1) {
						sendINT(1);
					} else {
						sendINT(0);
					}
					/* 종료조건5. SEEDER 요청을 받은 경우, 자신의 시더여부를 알려주고 종료. */
					break;
				} else {
					System.out.println("<UploadNeighborThread> badrequest");
					/* 종료조건6. 잘못된 요청. */
					break;
				}
			}
			connectionSocket.close();
			inputStream.close();
			outputStream.close();
			input.close();
			output.close();
		} catch (Exception e) {
		} finally {
			System.out.println("<UploadNeighborThread> end");
		}

	}

}
