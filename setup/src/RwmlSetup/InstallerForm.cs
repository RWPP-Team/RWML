using System;
using System.Drawing;
using System.IO;
using System.Windows.Forms;

namespace Rwml.Setup
{
    internal sealed class InstallerForm : Form
    {
        private readonly TextBox pathBox = new TextBox();
        private readonly Label status = new Label();
        private readonly TextBox logBox = new TextBox();
        private readonly Button browse = new Button();
        private readonly Button install = new Button();
        private readonly Button restore = new Button();
        private readonly Button close = new Button();
        private bool busy;

        internal InstallerForm()
        {
            Text = "RWML 安装程序";
            ClientSize = new Size(660, 420);
            MinimumSize = new Size(560, 360);
            StartPosition = FormStartPosition.CenterScreen;
            Font = new Font("Microsoft YaHei UI", 9F);

            var container = new TableLayoutPanel
            {
                Dock = DockStyle.Fill,
                ColumnCount = 3,
                RowCount = 4,
                Padding = new Padding(14),
            };
            container.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 72));
            container.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
            container.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 88));
            container.RowStyles.Add(new RowStyle(SizeType.Absolute, 34));
            container.RowStyles.Add(new RowStyle(SizeType.Absolute, 26));
            container.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
            container.RowStyles.Add(new RowStyle(SizeType.Absolute, 42));

            container.Controls.Add(new Label
            {
                Text = "游戏目录",
                Dock = DockStyle.Fill,
                TextAlign = ContentAlignment.MiddleLeft,
            }, 0, 0);

            pathBox.Dock = DockStyle.Fill;
            pathBox.Margin = new Padding(0, 3, 8, 3);
            container.Controls.Add(pathBox, 1, 0);

            browse.Text = "浏览…";
            browse.Dock = DockStyle.Fill;
            browse.Margin = new Padding(0, 2, 0, 2);
            browse.Click += (sender, args) => ChooseDirectory();
            container.Controls.Add(browse, 2, 0);

            status.Text = "正在查找游戏目录…";
            status.Dock = DockStyle.Fill;
            status.ForeColor = Color.FromArgb(90, 100, 92);
            status.TextAlign = ContentAlignment.MiddleLeft;
            container.Controls.Add(status, 1, 1);
            container.SetColumnSpan(status, 2);

            logBox.Dock = DockStyle.Fill;
            logBox.Multiline = true;
            logBox.ReadOnly = true;
            logBox.ScrollBars = ScrollBars.Vertical;
            logBox.BackColor = Color.FromArgb(247, 248, 242);
            logBox.BorderStyle = BorderStyle.FixedSingle;
            logBox.Margin = new Padding(0, 4, 0, 10);
            container.Controls.Add(logBox, 0, 2);
            container.SetColumnSpan(logBox, 3);

            var actions = new FlowLayoutPanel
            {
                Dock = DockStyle.Fill,
                FlowDirection = FlowDirection.RightToLeft,
                WrapContents = false,
                Margin = new Padding(0),
            };

            close.Text = "关闭";
            close.Width = 84;
            close.Height = 30;
            close.Click += (sender, args) => Close();
            actions.Controls.Add(close);

            install.Text = "安装";
            install.Width = 92;
            install.Height = 30;
            install.Click += (sender, args) => RunInstall();
            actions.Controls.Add(install);

            restore.Text = "恢复原版";
            restore.Width = 92;
            restore.Height = 30;
            restore.Click += (sender, args) => RunRestore();
            actions.Controls.Add(restore);

            container.Controls.Add(actions, 0, 3);
            container.SetColumnSpan(actions, 3);

            Controls.Add(container);
            AcceptButton = install;

            Shown += (sender, args) => Detect();
            FormClosing += (sender, args) =>
            {
                if (busy)
                {
                    args.Cancel = true;
                }
            };
        }

        private void Detect()
        {
            var detected = GameLocator.Detect();
            if (detected == null)
            {
                status.Text = "未自动找到游戏，请选择 Rusted Warfare 目录。";
                Append("未能从 Steam 注册表或卸载项定位游戏目录。");
                return;
            }
            pathBox.Text = detected;
            status.Text = "已找到游戏目录。";
            Append($"游戏目录：{detected}");
            Append(GameInstaller.IsRwmlJar(Path.Combine(detected, "game-lib.jar"))
                ? "当前 game-lib.jar 已经是 RWML 版本。"
                : "当前 game-lib.jar 为原版。");
        }

        private void ChooseDirectory()
        {
            using (var dialog = new FolderBrowserDialog())
            {
                dialog.Description = "选择 Rusted Warfare 安装目录（包含 game-lib.jar）";
                dialog.ShowNewFolderButton = false;
                if (dialog.ShowDialog(this) == DialogResult.OK)
                {
                    pathBox.Text = dialog.SelectedPath;
                    status.Text = GameLocator.IsGameDirectory(dialog.SelectedPath)
                        ? "目录有效。"
                        : "该目录缺少 game-lib.jar 或游戏可执行文件。";
                }
            }
        }

        private void RunInstall()
        {
            var directory = pathBox.Text.Trim();
            if (!GameLocator.IsGameDirectory(directory))
            {
                MessageBox.Show(this, "请选择包含 game-lib.jar 的游戏目录。", Text,
                    MessageBoxButtons.OK, MessageBoxIcon.Warning);
                return;
            }

            Run("正在安装…", () =>
            {
                var outcome = GameInstaller.Install(directory, AppendThreadSafe);
                status.Text = outcome.AlreadyCurrent ? "已是最新版本。" : $"已安装 RWML {outcome.Version}。";
                if (!outcome.OriginalBackedUp)
                {
                    Append("未创建原版备份：当前文件已经是 RWML，且目录中没有 game-lib.jar.original。");
                }
            });
        }

        private void RunRestore()
        {
            var directory = pathBox.Text.Trim();
            if (!GameLocator.IsGameDirectory(directory))
            {
                MessageBox.Show(this, "请选择包含 game-lib.jar 的游戏目录。", Text,
                    MessageBoxButtons.OK, MessageBoxIcon.Warning);
                return;
            }
            if (MessageBox.Show(this, "恢复原版 game-lib.jar？", Text,
                    MessageBoxButtons.YesNo, MessageBoxIcon.Question) != DialogResult.Yes)
            {
                return;
            }

            Run("正在恢复…", () =>
            {
                GameInstaller.Restore(directory, AppendThreadSafe);
                status.Text = "已恢复原版。";
            });
        }

        private void Run(string label, Action work)
        {
            if (busy)
            {
                return;
            }
            busy = true;
            SetControls(false);
            status.Text = label;
            Append(label);

            System.Threading.Tasks.Task.Run(() =>
            {
                try
                {
                    work();
                }
                catch (Exception failure)
                {
                    BeginInvoke((Action)(() =>
                    {
                        status.Text = failure.Message;
                        Append(failure.Message);
                        MessageBox.Show(this, failure.Message, Text,
                            MessageBoxButtons.OK, MessageBoxIcon.Error);
                    }));
                }
                finally
                {
                    BeginInvoke((Action)(() =>
                    {
                        busy = false;
                        SetControls(true);
                    }));
                }
            });
        }

        private void SetControls(bool enabled)
        {
            browse.Enabled = enabled;
            install.Enabled = enabled;
            restore.Enabled = enabled;
            close.Enabled = enabled;
            pathBox.Enabled = enabled;
        }

        private void AppendThreadSafe(string message)
        {
            if (InvokeRequired)
            {
                BeginInvoke((Action<string>)Append, message);
            }
            else
            {
                Append(message);
            }
        }

        private void Append(string message)
        {
            logBox.AppendText($"[{DateTime.Now:HH:mm:ss}] {message}{Environment.NewLine}");
        }
    }
}
