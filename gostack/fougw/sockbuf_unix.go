//go:build !windows

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
