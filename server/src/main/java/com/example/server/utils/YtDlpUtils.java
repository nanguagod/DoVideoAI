package com.example.server.utils;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
public class YtDlpUtils {

    @Value("${tool.ytdlp.path}")
    private String ytDlpPath;

    @Value("${tool.ffmpeg.dir}")
    private String ffmpegDir;

    @Value("${tool.ytdlp.cookie-file:./cookies.txt}")
    private String cookieFilePath;

    public File downloadVideo(String url) throws Exception {
        String tempDir = System.getProperty("java.io.tmpdir");
        String outputName = UUID.randomUUID().toString() + ".mp4";
        String outputPath = tempDir + File.separator + outputName;

        System.out.println("⬇️ [yt-dlp] 开始下载: " + url);

        // 从 URL 提取域名，决定策略
        String host = extractHost(url);
        System.out.println("🌐 [yt-dlp] 检测到平台: " + host);

        List<String> command = new ArrayList<>();
        command.add(ytDlpPath);

        // ==========================================
        // Cookie 策略：cookies.txt 文件优先，浏览器提取作为降级方案
        // cookies.txt 不受浏览器运行时 Cookie DB 锁定的影响，更稳定
        // ==========================================
        Path cookieFile = Path.of(cookieFilePath);
        if (Files.exists(cookieFile)) {
            command.add("--cookies");
            command.add(cookieFilePath);
            System.out.println("🔐 [yt-dlp] 使用 Cookie 文件: " + cookieFilePath);
        } else {
            // 降级：尝试浏览器 Cookie
            String cookiesFromBrowser = findBrowserWithCookies(host);
            if (cookiesFromBrowser != null) {
                command.add("--cookies-from-browser");
                command.add(cookiesFromBrowser);
                System.out.println("🔐 [yt-dlp] 使用浏览器 Cookie: " + cookiesFromBrowser);
            } else {
                System.out.println("⚠️ [yt-dlp] 未找到 Cookie 文件 (" + cookieFilePath + ") 或浏览器 Cookie，将尝试无 Cookie 下载（大概率失败）");
                System.out.println("💡 [yt-dlp] 建议: 使用 Chrome 扩展 'Get cookies.txt LOCALLY' 导出 B站 Cookie 为 cookies.txt 放到项目根目录");
            }
        }

        // ==========================================
        // 浏览器指纹伪装（yt-dlp 2024.11+ 支持）
        // 完整伪装 TLS 指纹 + HTTP/2 帧大小，比单纯改 UA 强得多
        // ==========================================
        command.add("--impersonate");
        command.add("chrome:windows");

        // 现代浏览器 User-Agent（impersonate 之外的额外保障）
        command.add("--user-agent");
        command.add("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36");

        // 请求头伪装（对所有平台有效）
        command.add("--add-header");
        command.add("Accept:text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8");
        command.add("--add-header");
        command.add("Accept-Language:zh-CN,zh;q=0.9,en;q=0.8");

        // 根据平台设置合适的 Referer
        command.add("--referer");
        command.add("https://" + host + "/");

        // Bilibili 专用参数：跳过 DASH/HLS 格式，直取 mp4
        if (host.contains("bilibili")) {
            command.add("--extractor-args");
            command.add("bilibili:player=html5;skip=hls,dash");
        }

        // ==========================================
        // 速率限制（避免触发 B站 频率限制）
        // ==========================================
        command.add("--sleep-requests");
        command.add("3");
        command.add("--sleep-interval");
        command.add("5");
        command.add("--max-sleep-interval");
        command.add("15");

        // ==========================================
        // 增强重试（B站反爬常需多次重试）
        // ==========================================
        command.add("--retries");
        command.add("5");
        command.add("--fragment-retries");
        command.add("10");
        command.add("--extractor-retries");
        command.add("5");

        command.add("--ffmpeg-location");
        command.add(ffmpegDir);

        command.add("-o");
        command.add(outputPath);

        // 其他选项
        command.add("--no-check-certificate");
        command.add("--no-playlist");

        command.add(url);

        // 打印完整命令便于调试
        System.out.println("🔧 [yt-dlp] 命令: " + String.join(" ", command));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);

        Process process = pb.start();

        StringBuilder logs = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("ERROR") || line.contains("Downloading") || line.contains("[Merger]")
                        || line.contains("Extracting") || line.contains("412") || line.contains("403")
                        || line.contains("WARNING") || line.contains("[download]")) {
                    System.out.println("cmd > " + line);
                }
                logs.append(line).append("\n");
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            String errorMsg = logs.toString();

            // ==========================================
            // B站 专属错误诊断
            // ==========================================
            if (host.contains("bilibili")) {
                // 412 — WBI 签名或 Cookie 问题
                if (errorMsg.contains("412") || errorMsg.contains("Precondition Failed")) {
                    String hint = "\n\n💡 B站诊断: HTTP 412 — WBI 签名验证失败或 Cookie 无效。\n"
                            + "   常见原因及解决方案:\n"
                            + "   1) Cookie 过期或无效 → 重新导出 cookies.txt (Chrome 扩展: Get cookies.txt LOCALLY)\n"
                            + "   2) 短时间内请求过多 → 等待 5-10 分钟后重试\n"
                            + "   3) yt-dlp 版本过旧 → 升级 yt-dlp (当前要求 ≥ 2024.11)\n"
                            + "   当前 Cookie 状态: " + (Files.exists(Path.of(cookieFilePath)) ? "cookies.txt 文件存在" : "cookies.txt 文件不存在，且" + (findBrowserWithCookies(host) == null ? "未找到可用浏览器" : "使用浏览器 Cookie")) + "\n";
                    throw new RuntimeException("B站反爬拦截 (HTTP 412) — WBI 签名/Cookie 验证失败。" + hint);
                }

                // 403 — 访问被拒
                if (errorMsg.contains("403") || errorMsg.contains("Forbidden")) {
                    String hint = "\n\n💡 B站诊断: HTTP 403 — 访问被拒。\n"
                            + "   常见原因:\n"
                            + "   1) Cookie 失效 → 重新导出 cookies.txt\n"
                            + "   2) 地区限制 → 该视频仅限中国大陆观看，可能需要代理\n"
                            + "   3) IP 被临时封禁 → 等待 10-30 分钟后重试\n"
                            + "   4) 浏览器指纹不匹配 → 已启用 --impersonate chrome:windows 伪装\n"
                            + "   当前 Cookie 状态: " + (Files.exists(Path.of(cookieFilePath)) ? "cookies.txt 文件存在" : "cookies.txt 文件不存在") + "\n";
                    throw new RuntimeException("B站反爬拦截 (HTTP 403) — 访问被拒。" + hint);
                }

                // Cookie 缺失
                if (errorMsg.contains("cookies are needed") || errorMsg.contains("Fresh cookies")) {
                    String hint = "\n\n💡 B站诊断: 需要有效 Cookie。\n"
                            + "   解决方案:\n"
                            + "   1) 在 Chrome 安装扩展 'Get cookies.txt LOCALLY'\n"
                            + "   2) 访问 bilibili.com 并登录账号\n"
                            + "   3) 点击扩展图标 → Export → 保存为 cookies.txt\n"
                            + "   4) 将 cookies.txt 放到项目根目录 (" + Path.of(cookieFilePath).toAbsolutePath().getParent() + ")\n"
                            + "   5) 重启后端服务后重试\n"
                            + "   当前 Cookie 状态: " + (Files.exists(Path.of(cookieFilePath)) ? "cookies.txt 文件存在" : "cookies.txt 文件不存在") + "\n";
                    throw new RuntimeException("B站要求登录凭证 (Cookie)。" + hint);
                }
            }

            // ==========================================
            // 通用错误处理（非 B站平台）
            // ==========================================
            if (errorMsg.contains("412") || errorMsg.contains("Precondition Failed")) {
                String hint = "\n\n💡 提示: " + host + " 需要登录凭证。请确保 cookies.txt 文件存在或浏览器已登录 " + host + "。";
                throw new RuntimeException("平台反爬拦截 (HTTP 412)，需要登录凭证。" + hint);
            }

            if (errorMsg.contains("403") || errorMsg.contains("Forbidden")) {
                String hint = "\n\n💡 提示: " + host + " 反爬拦截 (403)。"
                        + "\n   建议: 1) 导出 cookies.txt 文件  2) 检查 Cookie 是否过期  3) 等待后重试";
                throw new RuntimeException("平台反爬拦截 (HTTP 403) —— " + host + " 拒绝了请求。" + hint);
            }

            if (errorMsg.contains("cookies are needed") || errorMsg.contains("Fresh cookies")) {
                String hint = "\n\n💡 提示: " + host + " 需要 Cookie。请导出 cookies.txt 放到项目根目录。";
                throw new RuntimeException("平台要求浏览器 Cookie 验证。" + hint);
            }

            throw new RuntimeException("yt-dlp 下载失败: " + errorMsg);
        }

        File downloadedFile = new File(outputPath);
        if (!downloadedFile.exists()) {
            throw new RuntimeException("下载显示成功但文件未生成");
        }

        System.out.println("✅ [yt-dlp] 下载完成: " + (downloadedFile.length() / 1024) + "KB");
        return downloadedFile;
    }

    /**
     * 查找系统中可用的浏览器（按目标平台域名测试 Cookie 提取）
     */
    private String findBrowserWithCookies(String targetDomain) {
        // 按优先级尝试各浏览器
        String[] browsers = {"chrome", "edge", "firefox", "chromium", "opera", "brave"};
        for (String browser : browsers) {
            try {
                // 用 yt-dlp 自带的 cookie 提取测试
                List<String> testCmd = new ArrayList<>();
                testCmd.add(ytDlpPath);
                testCmd.add("--cookies-from-browser");
                testCmd.add(browser);
                testCmd.add("--print");
                testCmd.add("cookies_from_browser_works");
                testCmd.add(targetDomain);

                ProcessBuilder pb = new ProcessBuilder(testCmd);
                pb.redirectErrorStream(true);

                Process p = pb.start();
                StringBuilder out = new StringBuilder();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    String l;
                    while ((l = r.readLine()) != null) { out.append(l); }
                }
                p.waitFor();

                String result = out.toString();
                // 能成功提取 cookie 就返回
                if (!result.contains("ERROR") && !result.contains("not find")
                        && !result.contains("PermissionError") && !result.contains("Chrome requires")) {
                    System.out.println("✅ [yt-dlp] 浏览器 Cookie 可用: " + browser + " (" + targetDomain + ")");
                    return browser;
                } else {
                    System.out.println("⚠️ [yt-dlp] 浏览器 " + browser + " Cookie 提取失败: " + result.trim());
                }
            } catch (Exception e) {
                System.out.println("⚠️ [yt-dlp] 浏览器 " + browser + " 不可用: " + e.getMessage());
            }
        }
        System.out.println("❌ [yt-dlp] 未找到可用浏览器 Cookie（请关闭 Chrome 等浏览器后重试，或使用 cookies.txt 文件）");
        return null;
    }

    /**
     * 从 URL 中提取域名
     */
    private String extractHost(String url) {
        try {
            java.net.URI uri = new java.net.URI(url.startsWith("http") ? url : "https://" + url);
            String host = uri.getHost();
            if (host != null) {
                // 去掉 www. 前缀
                return host.startsWith("www.") ? host.substring(4) : host;
            }
        } catch (Exception ignored) {}
        return "unknown";
    }
}
