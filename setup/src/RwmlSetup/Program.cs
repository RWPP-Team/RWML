using System;
using System.Runtime.InteropServices;
using System.Windows.Forms;

namespace Rwml.Setup
{
    internal static class Program
    {
        private const int AttachParentProcess = -1;

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern bool AttachConsole(int processId);

        [STAThread]
        private static int Main(string[] args)
        {
            var options = Options.Parse(args);
            if (options.Mode != Mode.Ui)
            {
                AttachConsole(AttachParentProcess);
                return RunConsole(options);
            }

            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            Application.Run(new InstallerForm());
            return 0;
        }

        private static int RunConsole(Options options)
        {
            try
            {
                switch (options.Mode)
                {
                    case Mode.Detect:
                    {
                        var detected = GameLocator.Detect();
                        if (detected == null)
                        {
                            Console.WriteLine("not found");
                            return 2;
                        }
                        Console.WriteLine(detected);
                        return 0;
                    }
                    case Mode.Install:
                    {
                        var directory = options.Directory ?? GameLocator.Detect();
                        if (directory == null)
                        {
                            Console.WriteLine("未找到游戏目录，请使用 --dir 指定。");
                            return 2;
                        }
                        var outcome = GameInstaller.Install(directory, Console.WriteLine);
                        Console.WriteLine(outcome.AlreadyCurrent ? "unchanged" : "installed");
                        return 0;
                    }
                    case Mode.Restore:
                    {
                        var directory = options.Directory ?? GameLocator.Detect();
                        if (directory == null)
                        {
                            Console.WriteLine("未找到游戏目录，请使用 --dir 指定。");
                            return 2;
                        }
                        GameInstaller.Restore(directory, Console.WriteLine);
                        return 0;
                    }
                    default:
                        Console.WriteLine(Options.HelpText);
                        return 0;
                }
            }
            catch (Exception failure)
            {
                Console.WriteLine(failure.Message);
                return 1;
            }
        }

        private enum Mode
        {
            Ui,
            Detect,
            Install,
            Restore,
            Help,
        }

        private sealed class Options
        {
            internal const string HelpText =
                "rwml-setup [--install|--restore] [--dir <游戏目录>]\n" +
                "  --detect            自动定位游戏并输出目录\n" +
                "  --install           替换 game-lib.jar；省略 --dir 时自动定位\n" +
                "  --restore           从 game-lib.jar.original 恢复\n" +
                "无参数时打开安装界面。";

            internal Mode Mode { get; private set; }
            internal string Directory { get; private set; }

            internal static Options Parse(string[] args)
            {
                var options = new Options { Mode = args.Length == 0 ? Mode.Ui : Mode.Help };
                for (var index = 0; index < args.Length; index++)
                {
                    var argument = args[index];
                    switch (argument.ToLowerInvariant())
                    {
                        case "--detect":
                            options.Mode = Mode.Detect;
                            break;
                        case "--install":
                        case "--silent":
                            options.Mode = Mode.Install;
                            break;
                        case "--restore":
                            options.Mode = Mode.Restore;
                            break;
                        case "--dir":
                            if (index + 1 < args.Length)
                            {
                                options.Directory = args[++index].Trim('"');
                            }
                            break;
                        case "--help":
                        case "-h":
                            options.Mode = Mode.Help;
                            break;
                        default:
                            if (options.Directory == null && !argument.StartsWith("--", StringComparison.Ordinal))
                            {
                                options.Directory = argument.Trim('"');
                            }
                            break;
                    }
                }
                return options;
            }
        }
    }
}
