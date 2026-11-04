using System;
using System.IO;
using System.IO.Compression;
using System.Security.Cryptography;

namespace Rwml.Setup.Checks
{
    internal static class Program
    {
        private static int Main()
        {
            var failures = 0;

            // 定位依赖本机安装，CI 上没有游戏是正常情况。
            var detected = GameLocator.Detect();
            Console.WriteLine($"detect: {detected ?? "none"}");

            var fixture = Path.Combine(Path.GetTempPath(), "rwml-setup-check-" + Guid.NewGuid().ToString("n"));
            Directory.CreateDirectory(fixture);
            try
            {
                File.WriteAllText(Path.Combine(fixture, "Rusted Warfare.exe"), "stub");
                WritePlainJar(Path.Combine(fixture, "game-lib.jar"));
                var originalHash = Hash(Path.Combine(fixture, "game-lib.jar"));

                var outcome = GameInstaller.Install(fixture, Console.WriteLine);
                if (!outcome.OriginalBackedUp)
                {
                    Console.WriteLine("FAIL 未创建原版备份");
                    failures++;
                }
                if (!File.Exists(Path.Combine(fixture, "game-lib.jar.original")))
                {
                    Console.WriteLine("FAIL 备份文件不存在");
                    failures++;
                }
                if (Hash(Path.Combine(fixture, "game-lib.jar.original")) != originalHash)
                {
                    Console.WriteLine("FAIL 备份内容与原文件不一致");
                    failures++;
                }
                if (!GameInstaller.IsRwmlJar(Path.Combine(fixture, "game-lib.jar")))
                {
                    Console.WriteLine("FAIL 替换后的归档不是 RWML");
                    failures++;
                }
                if (!File.Exists(Path.Combine(fixture, "rwml-install.json")))
                {
                    Console.WriteLine("FAIL 未写入安装状态");
                    failures++;
                }

                var second = GameInstaller.Install(fixture, Console.WriteLine);
                if (!second.AlreadyCurrent)
                {
                    Console.WriteLine("FAIL 重复安装未识别为相同版本");
                    failures++;
                }

                GameInstaller.Restore(fixture, Console.WriteLine);
                if (Hash(Path.Combine(fixture, "game-lib.jar")) != originalHash)
                {
                    Console.WriteLine("FAIL 恢复后与原文件不一致");
                    failures++;
                }
            }
            finally
            {
                Directory.Delete(fixture, true);
            }

            Console.WriteLine(failures == 0 ? "all checks passed" : $"{failures} check(s) failed");
            return failures == 0 ? 0 : 1;
        }

        private static void WritePlainJar(string path)
        {
            using (var archive = ZipFile.Open(path, ZipArchiveMode.Create))
            {
                var entry = archive.CreateEntry("META-INF/MANIFEST.MF");
                using (var writer = new StreamWriter(entry.Open()))
                {
                    writer.Write("Manifest-Version: 1.0\n");
                }
            }
        }

        private static string Hash(string path)
        {
            using (var stream = File.OpenRead(path))
            using (var sha = SHA256.Create())
            {
                return BitConverter.ToString(sha.ComputeHash(stream)).Replace("-", string.Empty).ToLowerInvariant();
            }
        }
    }
}
