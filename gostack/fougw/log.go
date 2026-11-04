package fougw

import (
	"encoding/json"
	"fmt"
	"os"
	"sync"
	"sync/atomic"
)

// 日志出口设计：进程内（gomobile）模式下不能靠 stdout，Java 侧无法读子进程管道，
// 改为内存环形缓冲 + Java 定时拉取；CLI 模式下同时往 stdout 吐 JSON 行，方便肉眼调试。

const logCap = 300

var (
	logMu     sync.Mutex
	logRing   [logCap]string
	logCnt    int   // 累计写入条数
	logStart  int   // 环形缓冲的起始下标

	echoStdout atomic.Bool
)

// EnableStdout 打开 stdout 镜像（CLI 模式用）。返回是否打开成功之前的状态。
func enableStdout(on bool) {
	echoStdout.Store(on)
}

// jlog 记一条日志。
func jlog(format string, a ...interface{}) {
	msg := fmt.Sprintf(format, a...)

	logMu.Lock()
	if logCnt < logCap {
		logRing[(logStart+logCnt)%logCap] = msg
		logCnt++
	} else {
		// 满了就丢掉最旧的一条
		logRing[logStart] = msg
		logStart = (logStart + 1) % logCap
	}
	logMu.Unlock()

	if echoStdout.Load() {
		b, err := json.Marshal(map[string]interface{}{"t": "log", "m": msg})
		if err == nil {
			os.Stdout.Write(append(b, '\n'))
		}
	}
}

// LogsJSON 取走当前缓冲区里的全部日志（JSON 数组），并清空。
// Java 侧轮询调用，不会重复显示。
func LogsJSON() string {
	logMu.Lock()
	out := make([]string, 0, logCnt)
	for i := 0; i < logCnt; i++ {
		out = append(out, logRing[(logStart+i)%logCap])
	}
	logCnt = 0
	logStart = 0
	// 顺带把 stdout 镜像打开，CLI 下用得多
	logMu.Unlock()

	b, err := json.Marshal(out)
	if err != nil {
		return "[]"
	}
	return string(b)
}

// logStdout 只在 CLI 模式下输出一行 JSON 到 stdout。
func logStdout(m map[string]interface{}) {
	if !echoStdout.Load() {
		return
	}
	b, err := json.Marshal(m)
	if err != nil {
		return
	}
	os.Stdout.Write(append(b, '\n'))
}
