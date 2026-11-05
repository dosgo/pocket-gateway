//go:build windows

package fougw

import "syscall"

// Windows 没有等价的 SO_RCVBUF 读取接口，桌面 CLI 下只显示申请值。
// 这个包在 Android（gomobile）上走 sockbuf_unix.go，那里才读得到实际值。
func sockBufSizes(_ syscall.RawConn) (int, int) { return -1, -1 }

func sysctlMax(_ string) int { return -1 }
