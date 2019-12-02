import java.io.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class Torrent {

	/**
	 * 공유되는 멤버변수들.
	 */
	final static int MAX_PEER_NUM = 5; /* 프로토콜 내의 최대 PEER수 */
	final static int MAX_FRIENDS_NUM = 3; /* 한 피어가 유지할 수 있는 최대 친구의 */
	final static int MAX_SEND_CHUNK_NUM = 3; /* 한번의 전송당 전송할 수 있는 최대 chunk수*/
	final static int DOWNLOAD_RESPONSE_TIMEOUT = 5000; /* 다운로드 하는 Thread의 Timeout=5초*/
	final static int SOCKET_CONNECT_TIMEOUT = 5000; /* 소켓 연결 요청의 Timeout=5초 */
	final static int SERVER_CONNECTION_TIMEOUT = 30000; /* 서버에 재시작 시간 Timeout=30초 */
	final static int SLEEP_TIME = 3000; /* 결과값 출력을 위한 SLEEP TIME */
	final static String CONFIG_FILENAME = "configuration.txt"; /* 네트워크에 연결된 피어들의 정보를 담고있음. */
	final static String FILE_DIR_NAME = "File"; /* 전송할 파일이 들어있는 디렉토리 이름 */
	final static int CHUNK_SIZE = 1024 * 10; /* 전송에 사용할 청크의 크기 */

	static String[] peerAddressArr = new String[MAX_PEER_NUM]; /* config file에서 읽어온 피어들의 url */
	static int[] peerPortNumArr = new int[MAX_PEER_NUM]; /* config file에서 읽어온 피어들의 port number */
	static int[] peerSeederList = new int[MAX_PEER_NUM]; /* 해당 프로토콜 내에 존재하는 피어들의 시더여부 1=시더, 0=다운로 */

	static int[] friendsArr = new int[MAX_FRIENDS_NUM]; /* 해당 피어의 친구들에 대한 인덱스를 저장하고 있는 배열 */
	static int friendsIndex = 0; /* friend배열의 index */
	static byte[][] friendsChunkMap = new byte[MAX_PEER_NUM + 1][]; /* 모든 피어들의 청크맵을 저장해두고, 해당 피어가 친구일경우 여기서꺼내옴 */
	static String[] friendsStatus = new String[MAX_PEER_NUM + 1]; /*이하 동일*/
	static int[] friendsChunkSize = new int[MAX_PEER_NUM + 1]; /*이하 동일*/
	static int[] friendsChunkNum = new int[MAX_PEER_NUM + 1]; /*이하 동일*/

	/* 파일 정보에 대한 멤버변수
	 * 만약 자신이 시더일 경우 => 파일을 읽어오면서 초기화된다.
	 * 만약 자신이 시더가 아닌경우 => 다른 피어에게 정보를 요청하며 초기화된다. */
	static String thisFileName;
	static int thisChunkSize;
	static int thisChunkNum;
	static byte[] thisChunkMap;
	static byte[][] thisFileData;

	/* 모든 피어가 시더이면 true가 되어서 서버가 종료된다 */
	static boolean allPeerIsSeeder = false;

	/* Type : 입출력
	 * 역할 : config파일을 읽어와 배열에 정보를 저장하는 메서드.
	 * return : boolean (true -> 성공한 경우, false-> 실패한 경우)
	 */
	static boolean readConfig() throws IOException {

		// check file exists
		Path p = Paths.get(System.getProperty("user.dir"));
		File configFile = new File(p.toString(), CONFIG_FILENAME);
		// exists
		if (configFile.exists()) {
			FileInputStream fis = new FileInputStream(configFile);
			BufferedReader br = new BufferedReader(new InputStreamReader(fis));

			for (int i = 0; i < MAX_PEER_NUM; i++) {
				StringTokenizer stk = new StringTokenizer(br.readLine(), " ");
				peerAddressArr[i] = stk.nextToken();
				peerPortNumArr[i] = Integer.parseInt(stk.nextToken());
			}
			fis.close();
			br.close();
			return true;
			// not exists
		} else {
			return false;
		}
	}

	/* Type : 입출력
	 * 역할 : 입력받은 파일 이름을 File경로에서 읽어와 배열에 정보를 저장하는 메서드.
	 * 파일이 있는 시더일 경우, 파일에 대한 정보를 초기화한다.
	 * 파일이 없는 시더일 경우, 파일에 대한 정보가 없으므로 다운로더가 받아와야함.
	 * return : boolean (true -> 성공한 경우, false-> 실패한 경우)
	 */
	static boolean readFile(String fileName) throws IOException {

		/* 파일존재여부 체크 */
		Path p = Paths.get(System.getProperty("user.dir"), FILE_DIR_NAME);
		File file = new File(p.toString(), fileName);
		/* 파일이 존재하는 경우 */
		if (file.exists()) {
			thisChunkSize = CHUNK_SIZE;
			thisChunkNum = (int) Math.ceil((double) file.length() / thisChunkSize);

			FileInputStream fis = new FileInputStream(file);
			BufferedInputStream bis = new BufferedInputStream(fis);

			thisFileData = new byte[thisChunkNum][];
			for (int i = 0; i < thisChunkNum; i++) {
				thisFileData[i] = new byte[thisChunkSize];
			}

			int index = 0;
			int numRead = 0;

			while (index < thisChunkNum
					&& (numRead = bis.read(thisFileData[index], 0, thisFileData[index].length)) != -1) {
				index++;
			}

			thisChunkMap = new byte[thisChunkNum];
			for (int i = 0; i < thisChunkNum; i++) {
				thisChunkMap[i] = (byte) 1;
			}

			/* 해당 피어가 시더라고 설정한다, 순서는 config파일의 index */
			peerSeederList[0] = 1;

			fis.close();
			bis.close();
			return true;
		}
		/* 파일이 존재하지 않는 경우 */
		else {
			return false;
		}
	}

	/* Type : 메인함수
	 * 역할 : 필요한 파일들을 읽고, 공유변수들을 메모리에 올린다.
	 * Server를 실행시키고, Download Thread를 실행시킨다.
	 * 해당 피어가 시더가 되면, 해당 경로에 파일을 쓴다.
	 * return : void
	 */
	public static void main(String[] args) {
		try {
			if (args.length < 1) {
				System.out.println("사용법 : java Torrent [파일이름]");
				return;
			}
			System.out.println("<Torrent> 쓰레드 시작");

			/* 파일이름은 console에서 받는다 */
			thisFileName = args[0];
			for (int i = 0; i < friendsArr.length; i++) {
				friendsArr[i] = -1; //몇번 peer랑 친구인지를 저장하는 배열 (몇번째줄이 peer인지)
			}

			if (!readConfig()) {
				System.out.println("<Torrent> configuration.txt 파일 읽기 실패");
				return;
			}

			if (!readFile(thisFileName)) {
				System.out.println("<Torrent> 파일을 가지고 있지 않습니다. 해당 파일을 다운로드합니다.");
			}

			PeerServerThread server = new PeerServerThread(peerPortNumArr[0]);
			Thread serverThread = new Thread(server);
			serverThread.start();

			/* 친구의 숫자만큼 DownloadNeighborThread를 만든다 */
			for (int i = 1 ; i <= MAX_FRIENDS_NUM; i++) {
				DownloadNeighborThread downloader = new DownloadNeighborThread(i);
				Thread downloadThread = new Thread(downloader);
				downloadThread.start();
			}

			/* 해당 피어가 시더가 된 경우, 즉 파일 정보를 다 받아온 경우
			 * 원래 시더였을 경우, 파일정보를 다 읽어와서 저장하고 있음*/
			while (true) {
				if (peerSeederList[0] == 1)
					break;
				Thread.sleep(SLEEP_TIME);
			}

			/* 파일을 해당 경로에 쓴다. */
			System.out.println("<Torrent> 파일 다운로드를 완료했습니다. 해당 파일을 생성합니다.");
			FileOutputStream fos = new FileOutputStream(FILE_DIR_NAME + "/" + thisFileName);
			BufferedOutputStream bos = new BufferedOutputStream(fos);
			for (int i = 0; i < thisChunkNum; i++) {
				bos.write(thisFileData[i], 0, thisFileData[i].length);
			}

			fos.close();

			System.out.println("<Torrent> 쓰레드 종료");
		} catch (Exception e) {
			System.out.println("<Torrent> 에러가 발생해 메인 메서드를 종료합니다.");
			e.printStackTrace();
		}
	}
}
