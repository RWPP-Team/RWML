using System;
using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Reflection;
using System.Security.Cryptography;
using System.Text;

namespace Rwml.Setup
{
    internal sealed class InstallOutcome
    {
        internal string GameDirectory;
        internal string Version;
        internal string InstalledSha256;
        internal bool OriginalBackedUp;
        internal bool AlreadyCurrent;
        internal bool BundledRuntime;
    }

    /// <summary>Replaces game-lib.jar with the bundled host and keeps a vanilla backup.</summary>
    internal static class GameInstaller
    {
        private const string JarName = "game-lib.jar";
        private const string BackupName = "game-lib.jar.original";
        private const string StateName = "rwml-install.json";
        private const string Marker = "io/github/rwml/desktop/DesktopRuntime.class";
        private const string PayloadResource = "game-lib.jar";
        private const string JreResource = "rwml-jre.zip";
        private const string RuntimeName = "rwml-runtime";
        private const string RuntimeVersionName = "jre-version.txt";

        private static readonly string[] GameProcesses = { "Rusted Warfare", "Rusted Warfare - 64" };

        internal static string Version
        {
            get
            {
                var version = Assembly.GetExecutingAssembly().GetName().Version;
                return version == null ? "0.0.0" : $"{version.Major}.{version.Minor}.{version.Build}";
            }
        }

        internal static InstallOutcome Install(string directory, Action<string> log)
        {
            if (!Environment.Is64BitOperatingSystem)
            {
                throw new InvalidOperationException("RWML 需要 64 位 Windows。");
            }
            if (!GameLocator.IsGameDirectory(directory))
            {
                throw new InvalidOperationException("所选目录没有 game-lib.jar 或游戏可执行文件。");
            }
            EnsureGameStopped();

            var target = Path.Combine(directory, JarName);
            using (var payload = OpenPayload())
            {
                if (payload == null)
                {
                    throw new InvalidOperationException("安装包内缺少 game-lib.jar，无法继续。");
                }

                var payloadHash = CopyAndHash(payload, Path.Combine(Path.GetTempPath(), "rwml-payload.jar"));
                ValidatePayload(Path.Combine(Path.GetTempPath(), "rwml-payload.jar"));
                var bundledRuntime = InstallRuntime(directory, log);

                var installedHash = Sha256(target);
                if (string.Equals(payloadHash, installedHash, StringComparison.OrdinalIgnoreCase))
                {
                    log("当前版本已经安装，无需替换。");
                    var current = new InstallOutcome
                    {
                        GameDirectory = directory,
                        Version = Version,
                        InstalledSha256 = installedHash,
                        OriginalBackedUp = File.Exists(Path.Combine(directory, BackupName)),
                        AlreadyCurrent = true,
                        BundledRuntime = bundledRuntime,
                    };
                    WriteState(current);
                    return current;
                }

                var backup = Path.Combine(directory, BackupName);
                var backedUp = File.Exists(backup);
                if (!backedUp && !IsRwmlJar(target))
                {
                    File.Copy(target, backup, false);
                    backedUp = true;
                    log("已备份原版 game-lib.jar");
                }

                var staged = Path.Combine(directory, JarName + ".new");
                try
                {
                    File.Copy(Path.Combine(Path.GetTempPath(), "rwml-payload.jar"), staged, true);
                    if (File.Exists(target))
                    {
                        try
                        {
                            File.Replace(staged, target, null);
                        }
                        catch (IOException)
                        {
                            // Some file systems do not support Replace; fall back to an overwrite.
                            File.Copy(staged, target, true);
                            SafeDelete(staged);
                        }
                    }
                    else
                    {
                        File.Move(staged, target);
                    }
                }
                catch (UnauthorizedAccessException)
                {
                    SafeDelete(staged);
                    throw new IOException("没有写入权限。请关闭游戏后以管理员身份运行安装程序。");
                }
                catch (IOException)
                {
                    SafeDelete(staged);
                    throw;
                }

                var result = new InstallOutcome
                {
                    GameDirectory = directory,
                    Version = Version,
                    InstalledSha256 = Sha256(target),
                    OriginalBackedUp = backedUp,
                    BundledRuntime = bundledRuntime,
                };
                if (!string.Equals(result.InstalledSha256, payloadHash, StringComparison.OrdinalIgnoreCase))
                {
                    throw new IOException("写入后的文件校验失败，安装未完成。");
                }

                WriteState(result);
                log($"已安装 RWML {result.Version}");
                return result;
            }
        }

        internal static bool Restore(string directory, Action<string> log)
        {
            if (!GameLocator.IsGameDirectory(directory))
            {
                throw new InvalidOperationException("所选目录没有 game-lib.jar 或游戏可执行文件。");
            }
            var backup = Path.Combine(directory, BackupName);
            if (!File.Exists(backup))
            {
                throw new InvalidOperationException("没有找到原版备份 game-lib.jar.original。");
            }
            EnsureGameStopped();

            var target = Path.Combine(directory, JarName);
            try
            {
                File.Copy(backup, target, true);
            }
            catch (UnauthorizedAccessException)
            {
                throw new IOException("没有写入权限。请关闭游戏后以管理员身份运行安装程序。");
            }
            log("已恢复原版 game-lib.jar");
            RemoveRuntime(directory, log);
            return true;
        }

        /// <summary>Installs the bundled JRE so the first launch does not download one.</summary>
        private static bool InstallRuntime(string directory, Action<string> log)
        {
            using (var payload = OpenResource(JreResource))
            {
                if (payload == null)
                {
                    log("安装包未包含 Java 运行时，首次启动时会自动下载。");
                    return false;
                }

                var root = Path.Combine(directory, RuntimeName);
                var existing = FindJavaHome(root);
                var feature = existing == null ? "25" : FeatureOf(existing);
                var marker = Path.Combine(root, RuntimeVersionName);
                if (existing != null && File.Exists(marker)
                    && string.Equals(File.ReadAllText(marker).Trim(), feature, StringComparison.Ordinal))
                {
                    log("内置 Java " + feature + " 运行时已存在。");
                    return true;
                }

                var staging = root + ".new";
                SafeDeleteDirectory(staging);
                Directory.CreateDirectory(staging);
                log("正在解压内置 Java 运行时…");
                try
                {
                    using (var archive = new ZipArchive(payload, ZipArchiveMode.Read))
                    {
                        archive.ExtractToDirectory(staging);
                    }

                    var javaHome = FindJavaHome(staging);
                    if (javaHome == null)
                    {
                        throw new InvalidOperationException("安装包内的 Java 运行时缺少 bin\\java.exe。");
                    }
                    feature = FeatureOf(javaHome);
                    var normalized = Path.Combine(staging, "jdk-" + feature);
                    if (!PathsEqual(javaHome, staging) && !PathsEqual(javaHome, normalized))
                    {
                        if (Directory.Exists(normalized))
                        {
                            Directory.Delete(normalized, true);
                        }
                        Directory.Move(javaHome, normalized);
                    }
                    File.WriteAllText(Path.Combine(staging, RuntimeVersionName), feature);

                    SafeDeleteDirectory(root);
                    Directory.Move(staging, root);
                    log("已安装内置 Java " + feature + " 运行时。");
                    return true;
                }
                catch (Exception)
                {
                    SafeDeleteDirectory(staging);
                    throw;
                }
            }
        }

        private static void RemoveRuntime(string directory, Action<string> log)
        {
            var root = Path.Combine(directory, RuntimeName);
            if (!File.Exists(Path.Combine(root, RuntimeVersionName)))
            {
                return;
            }
            try
            {
                Directory.Delete(root, true);
                log("已移除内置 Java 运行时。");
            }
            catch (Exception)
            {
                log("内置 Java 运行时无法删除，可手动删除 rwml-runtime 目录。");
            }
        }

        /// <summary>Finds a Java home inside the extracted archive; the ZIP usually carries one top-level folder.</summary>
        private static string FindJavaHome(string root)
        {
            if (!Directory.Exists(root))
            {
                return null;
            }
            if (File.Exists(Path.Combine(root, "bin", "java.exe")))
            {
                return root;
            }
            foreach (var child in Directory.GetDirectories(root))
            {
                if (File.Exists(Path.Combine(child, "bin", "java.exe")))
                {
                    return child;
                }
                foreach (var grandchild in Directory.GetDirectories(child))
                {
                    if (File.Exists(Path.Combine(grandchild, "bin", "java.exe")))
                    {
                        return grandchild;
                    }
                }
            }
            return null;
        }

        private static string FeatureOf(string javaHome)
        {
            var release = Path.Combine(javaHome, "release");
            if (File.Exists(release))
            {
                foreach (var line in File.ReadAllLines(release))
                {
                    if (!line.StartsWith("JAVA_VERSION=", StringComparison.Ordinal))
                    {
                        continue;
                    }
                    var value = line.Substring("JAVA_VERSION=".Length).Trim().Trim('"');
                    var first = value.Split(new[] { '.', '-', '_', '+' }, StringSplitOptions.RemoveEmptyEntries);
                    int major;
                    if (first.Length > 0 && int.TryParse(first[0], out major))
                    {
                        return major == 1 && first.Length > 1 ? first[1] : major.ToString();
                    }
                }
            }
            return "25";
        }

        private static bool PathsEqual(string left, string right)
        {
            return string.Equals(
                Path.GetFullPath(left).TrimEnd(Path.DirectorySeparatorChar),
                Path.GetFullPath(right).TrimEnd(Path.DirectorySeparatorChar),
                StringComparison.OrdinalIgnoreCase);
        }

        private static void SafeDeleteDirectory(string path)
        {
            try
            {
                if (Directory.Exists(path))
                {
                    Directory.Delete(path, true);
                }
            }
            catch (Exception)
            {
                // A leftover staging directory is replaced on the next install.
            }
        }

        internal static bool IsRwmlJar(string path)
        {
            try
            {
                if (!File.Exists(path))
                {
                    return false;
                }
                using (var archive = ZipFile.OpenRead(path))
                {
                    return archive.GetEntry(Marker) != null;
                }
            }
            catch (Exception)
            {
                return false;
            }
        }

        private static Stream OpenPayload()
        {
            return OpenResource(PayloadResource);
        }

        private static Stream OpenResource(string name)
        {
            var assembly = Assembly.GetExecutingAssembly();
            var stream = assembly.GetManifestResourceStream(name);
            if (stream != null)
            {
                return stream;
            }
            var beside = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, name);
            return File.Exists(beside) ? File.OpenRead(beside) : null;
        }

        private static void ValidatePayload(string path)
        {
            try
            {
                using (var archive = ZipFile.OpenRead(path))
                {
                    if (archive.GetEntry(Marker) == null)
                    {
                        throw new InvalidOperationException("安装包内的 game-lib.jar 不是 RWML 构建产物。");
                    }
                }
            }
            catch (InvalidDataException)
            {
                throw new InvalidOperationException("安装包内的 game-lib.jar 已损坏。");
            }
        }

        private static string CopyAndHash(Stream source, string destination)
        {
            using (var output = File.Create(destination))
            using (var hash = SHA256.Create())
            using (var crypto = new CryptoStream(output, hash, CryptoStreamMode.Write))
            {
                source.CopyTo(crypto);
                crypto.FlushFinalBlock();
                return ToHex(hash.Hash);
            }
        }

        private static string Sha256(string path)
        {
            if (!File.Exists(path))
            {
                return string.Empty;
            }
            using (var stream = File.OpenRead(path))
            using (var hash = SHA256.Create())
            {
                return ToHex(hash.ComputeHash(stream));
            }
        }

        private static string ToHex(byte[] value)
        {
            var builder = new StringBuilder(value.Length * 2);
            foreach (var item in value)
            {
                builder.Append(item.ToString("x2"));
            }
            return builder.ToString();
        }

        private static void EnsureGameStopped()
        {
            foreach (var name in GameProcesses)
            {
                if (Process.GetProcessesByName(name).Length > 0)
                {
                    throw new InvalidOperationException("游戏正在运行，请先退出游戏。");
                }
            }
        }

        private static void WriteState(InstallOutcome outcome)
        {
            var state = new StringBuilder();
            state.Append("{\n");
            state.Append("  \"version\": \"").Append(Escape(outcome.Version)).Append("\",\n");
            state.Append("  \"installedAt\": \"").Append(DateTime.UtcNow.ToString("o")).Append("\",\n");
            state.Append("  \"sha256\": \"").Append(Escape(outcome.InstalledSha256)).Append("\",\n");
            state.Append("  \"runtime\": \"").Append(outcome.BundledRuntime ? "bundled" : "system").Append("\",\n");
            state.Append("  \"originalBackup\": ").Append(outcome.OriginalBackedUp ? "true" : "false").Append('\n');
            state.Append("}\n");
            File.WriteAllText(Path.Combine(outcome.GameDirectory, StateName), state.ToString(), Encoding.UTF8);
        }

        private static string Escape(string value) =>
            (value ?? string.Empty).Replace("\\", "\\\\").Replace("\"", "\\\"");

        private static void SafeDelete(string path)
        {
            try
            {
                if (File.Exists(path))
                {
                    File.Delete(path);
                }
            }
            catch (Exception)
            {
                // A leftover staging file is harmless; the next run overwrites it.
            }
        }
    }
}
