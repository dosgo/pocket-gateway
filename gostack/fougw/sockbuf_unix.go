//go:build linux || android

// 这个包只在 Android（gomobile bind 的目标）和 Linux CLI 下编译。
// 没有 Windows 版本：宿主是 Windows 只影响 go 命令本身，交叉编译出来的
// 目标是 android/arm*，不会也不需要任何 Windows 侧实现。
package fougw

import (
	"os"
	"strconv"
	"strings"
	"syscall"
)

// sockBufSizes 读回内核实际给这个 socket 分配的收发缓冲。
// 注意 Linux 的 SO_RCVBUF / SO_SNDBUF 返回值包含 skb 元数据开销，
// 通常是 setsockopt 传入值的两倍；若明显不到两倍，就是被
// rmem_max / wmem_max 截断了。
func sockBufSizes(raw syscall.RawConn) (int, int) {
	rx, tx := -1, -1
	err := raw.Control(func(fd uintptr) {
		rx, _ = syscall.GetsockoptInt(int(fd), syscall.SOL_SOCKET, syscall.SO_RCVBUF)
		tx, _ = syscall.GetsockoptInt(int(fd), syscall.SOL_SOCKET, syscall.SO_SNDBUF)
	})
	if err != nil {
		return -1, -1
	}
	return rx, tx
}

// setOutTCPBuf 在 connect() 之前调大出站 TCP socket 的收发缓冲。
// window scale 是 SYN 里协商的，连接建立之后再设就来不及了；
// 失败也不阻断连接，内核只是按 rmem_max/wmem_max 静默截断。
func setOutTCPBuf(fd uintptr) {
	_ = syscall.SetsockoptInt(int(fd), syscall.SOL_SOCKET, syscall.SO_RCVBUF, outTCPBuf)
	_ = syscall.SetsockoptInt(int(fd), syscall.SOL_SOCKET, syscall.SO_SNDBUF, outTCPBuf)
}

// sysctlMax 读 /proc/sys/net/core/{r,w}mem_max，读不到返回 -1。
func sysctlMax(path string) int {
	b, err := os.ReadFile(path)
	if err != nil {
		return -1
	}
	v, err := strconv.Atoi(strings.TrimSpace(string(b)))
	if err != nil {
		return -1
	}
	return v
}
