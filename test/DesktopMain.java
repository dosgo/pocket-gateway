import com.dosgo.gateway.net.HttpProxyServer;
import com.dosgo.gateway.net.Socks5Server;

/** 桌面端集成测试入口：把 SOCKS5 / HTTP 跑在本机高位端口（FOU 已改用 Go 二进制）。 */
public class DesktopMain {
    public static void main(String[] args) throws Exception {
        int socks = 11080, http = 18080;
        new Socks5Server(socks).start();
        new HttpProxyServer(http).start();
        System.out.println("READY");
        Thread.sleep(600000);
    }
}
