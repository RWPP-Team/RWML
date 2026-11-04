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
    }

    /// <summary>Replaces game-lib.jar with the bundled host and keeps a vanilla backup.</summary>
    internal static class GameInstaller
    {
        private const string JarName = "game-lib.jar";
        private const string BackupName = "game-lib.jar.original";
        private const string StateName = "rwml-install.json";
        private const string Marker = "io/github/rwml/desktop/DesktopRuntime.class";
        private const string PayloadResource = "game-lib.jar";

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

                var installedHash = Sha256(target);
                if (string.Equals(payloadHash, installedHash, StringComparison.OrdinalIgnoreCase))
                {
                    log("当前版本已经安装，无需替换。");
                    return new InstallOutcome
                    {
                        GameDirectory = directory,
                        Version = Version,
                        InstalledSha256 = installedHash,
                        OriginalBackedUp = File.Exists(Path.Combine(directory, BackupName)),
                        AlreadyCurrent = true,
                    };
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
            return true;
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
            var assembly = Assembly.GetExecutingAssembly();
            var stream = assembly.GetManifestResourceStream(PayloadResource);
            if (stream != null)
            {
                return stream;
            }
            var beside = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, JarName);
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
