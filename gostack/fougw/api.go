// Package fougw 是 FOU（Foo-over-UDP / IP-in-UDP）用户态网关。
//
// 原始 IPv4 包从外层 UDP 载荷进来，直接注入 gVisor netstack 的 channel endpoint，
// 由 netstack 完整实现 TCP/UDP 状态机，应用侧拿到的是正常的 net.Conn，
// 再与真实 socket 对接。不需要 root、不需要 VpnService。
//
// 对外只暴露 6 个函数，参数和返回值都是 string/bool/error，
// 以满足 gomobile bind 的类型约束。
package fougw

// Version 返回版本号，Java 侧用于界面展示。
func Version() string {
	return "2.0-gvisor"
}

// SetStdout 控制是否把日志同时写到 stdout（CLI 调试用，App 内保持关闭）。
func SetStdout(on bool) {
	enableStdout(on)
}
