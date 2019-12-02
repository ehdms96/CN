import java.net.*;

public class PeerServerThread implements Runnable {

	ServerSocket welcomeSocket;
	Socket connectionSocket;

	int listenPort; /* Restarting the server if no connection request is available */

	/* Type : 생성자
	 * 역할 : 메인쓰레드 Torrent로부터 listenport를 받아온다.
	 * return : void
	 */
	public PeerServerThread(int listenPort) {
		this.listenPort = listenPort;
	}

	/* Type : 쓰레드 메인
	 * 역할 : 서버 역할을 수행하는 쓰레드이다. 해당 listen port로 요청이 들어오면,
	 * 해당 요청을 UploadNeighborThread에게 위임한 후에 자신은 다시 다른 요청을 대기한다.
	 * return : void
	 */
	@Override
	public void run() {
		System.out.println("<PeerServerThread> Start");
		/* 연결된 모든 5명의 피어가 모두 Seeder일경우 종료된다. DownloadNeighborThread에서 변경된다. */
		while (!Torrent.allPeerIsSeeder) {
			try {

				welcomeSocket = new ServerSocket(listenPort);

				/* 해당 소켓이 연결이 들어오지 않을 경우 종료하는 Timeout을 설정한다. */
				welcomeSocket.setSoTimeout(Torrent.SERVER_CONNECTION_TIMEOUT);
				while (true) {
					/* 실제 연결을 위한 Connection Socket이다.
					 * uploaderNeighborThread에게 일을 위임시키고 다시 accept()상태가 된다.
					 */
					connectionSocket = welcomeSocket.accept();
					System.out.println("<PeerServerThread> Connection Request _ Make UploadThread");
					/* 연결을 위한 소킷을 uploaderNeighborThread에게 위임한다 */
					UploadNeighborThread uploadNeighbor = new UploadNeighborThread(connectionSocket);
					Thread thread = new Thread(uploadNeighbor);
					thread.start();
				}
			} catch (Exception e) {
				try {
					/* 해당 소켓 연결이 들어오지 않을 경우, welcomeSocket을 삭제하고 while문을 통해 다시 서버를 시작한다.
					 * 서버 시작시, 모든 피어가 Seeder일경우 종료된다.
					 */
					welcomeSocket.close();
				} catch (Exception f) {
				}
			}
		}
		System.out.println("<PeerServerThread> End");
	}
}
