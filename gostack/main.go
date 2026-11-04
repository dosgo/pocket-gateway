// CLI 外壳：把 fougw 包包一层命令行入口，用于桌面端调试与真机 adb 测试。
// Android App 走 gomobile bind（同包同代码），不经过这个 main。
package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"os/signal"
	"syscall"

	"fougw/fougw"
)

var (
	flagPort    = flag.Int("port", 5555, "FOU UDP 监听端口")
	flagMTU     = flag.Int("mtu", 1400, "隧道 MTU（决定 TCP MSS = mtu-40）")
	flagVerbose = flag.Bool("v", false, "打印每条连接的日志")
	flagWatch   = flag.Bool("watch-stdin", false, "stdin 关闭即退出（防孤儿进程）")
)

func main() {
	flag.Parse()

	fougw.SetStdout(true)
	cfg, _ := json.Marshal(fougw.Config{Port: *flagPort, MTU: *flagMTU, Verbose: *flagVerbose})
	if err := fougw.Start(string(cfg)); err != nil {
		fmt.Fprintf(os.Stderr, "%v\n", err)
		os.Exit(2)
	}

	if *flagWatch {
		go func() {
			buf := make([]byte, 256)
			for {
				if _, err := os.Stdin.Read(buf); err != nil {
					fougw.Stop()
					os.Exit(0)
				}
			}
		}()
	}

	sig := make(chan os.Signal, 1)
	signal.Notify(sig, syscall.SIGINT, syscall.SIGTERM)
	<-sig
	fougw.Stop()
	os.Exit(0)
}
