// Vanity Player Installer for Windows.
//
// A small program that always installs the newest Vanity Player: it asks GitHub Releases for
// latest.json, downloads the Windows installer named there, checks its SHA-256 and runs it.
// Because it always asks, this file never goes out of date.
//
// Built in CI with the C# compiler that ships with Windows (.NET Framework 4), so it needs
// nothing installed to run. Keep it to C# 5 syntax.

using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Net;
using System.Security.Cryptography;
using System.Web.Script.Serialization;
using System.Windows.Forms;

[assembly: System.Reflection.AssemblyTitle("Vanity Player Installer")]
[assembly: System.Reflection.AssemblyProduct("Vanity Player")]
[assembly: System.Reflection.AssemblyVersion("1.0.0.0")]

namespace VanityInstaller
{
    static class Program
    {
        [STAThread]
        static void Main()
        {
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            Application.Run(new InstallerForm());
        }
    }

    class InstallerForm : Form
    {
        const string ManifestUrl = "https://github.com/NoVanity99/Music-Player/releases/latest/download/latest.json";

        readonly Label title = new Label();
        readonly Label status = new Label();
        readonly ProgressBar bar = new ProgressBar();
        readonly Button button = new Button();
        readonly BackgroundWorker worker = new BackgroundWorker();

        public InstallerForm()
        {
            Text = "Vanity Player Installer";
            ClientSize = new Size(460, 220);
            FormBorderStyle = FormBorderStyle.FixedSingle;
            MaximizeBox = false;
            StartPosition = FormStartPosition.CenterScreen;
            BackColor = Color.FromArgb(18, 18, 20);
            ForeColor = Color.White;
            Font = new Font("Segoe UI", 10f);
            try { Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath); } catch (Exception) { }

            title.Text = "Vanity Player";
            title.Font = new Font("Segoe UI Semibold", 20f);
            title.SetBounds(30, 26, 400, 44);

            status.ForeColor = Color.FromArgb(170, 170, 178);
            status.SetBounds(32, 82, 396, 44);

            bar.SetBounds(32, 132, 396, 12);
            bar.Style = ProgressBarStyle.Marquee;

            button.SetBounds(308, 166, 120, 32);
            button.FlatStyle = FlatStyle.Flat;
            button.BackColor = Color.FromArgb(232, 67, 107);
            button.ForeColor = Color.White;
            button.FlatAppearance.BorderSize = 0;
            button.Visible = false;

            Controls.Add(title);
            Controls.Add(status);
            Controls.Add(bar);
            Controls.Add(button);

            worker.WorkerReportsProgress = true;
            worker.DoWork += Work;
            worker.ProgressChanged += delegate(object s, ProgressChangedEventArgs e)
            {
                if (e.UserState != null) status.Text = (string)e.UserState;
                if (e.ProgressPercentage >= 0)
                {
                    bar.Style = ProgressBarStyle.Continuous;
                    bar.Value = Math.Min(100, e.ProgressPercentage);
                }
                else bar.Style = ProgressBarStyle.Marquee;
            };
            worker.RunWorkerCompleted += Finished;
            Shown += delegate { Start(); };
        }

        void Start()
        {
            button.Visible = false;
            bar.Visible = true;
            bar.Style = ProgressBarStyle.Marquee;
            status.Text = "Finding the latest version...";
            worker.RunWorkerAsync();
        }

        void Work(object sender, DoWorkEventArgs e)
        {
            // GitHub needs TLS 1.2, which .NET Framework 4 doesn't switch on by itself.
            ServicePointManager.SecurityProtocol = (SecurityProtocolType)3072;

            string json;
            using (var web = NewClient()) json = web.DownloadString(ManifestUrl);
            var manifest = new JavaScriptSerializer().Deserialize<Dictionary<string, object>>(json);
            var version = Convert.ToString(manifest["version"]);
            var asset = manifest.ContainsKey("windows") ? manifest["windows"] as Dictionary<string, object> : null;
            if (asset == null) throw new InvalidOperationException("The latest release has no Windows version yet.");
            var url = Convert.ToString(asset["url"]);
            var sha256 = Convert.ToString(asset["sha256"]);
            var size = Convert.ToInt64(asset["size"]);
            if (!url.StartsWith("https://github.com/NoVanity99/Music-Player/", StringComparison.OrdinalIgnoreCase))
                throw new InvalidOperationException("The download address looks wrong.");

            var dir = Path.Combine(Path.GetTempPath(), "VanityPlayerInstaller");
            Directory.CreateDirectory(dir);
            var file = Path.Combine(dir, "VanityPlayer-" + version + ".msi");

            worker.ReportProgress(0, "Downloading Vanity Player " + version + "...");
            var request = (HttpWebRequest)WebRequest.Create(url);
            request.UserAgent = "VanityPlayer-Installer";
            using (var response = request.GetResponse())
            using (var input = response.GetResponseStream())
            using (var output = File.Create(file))
            {
                var buffer = new byte[81920];
                long done = 0;
                int read, last = -1;
                while ((read = input.Read(buffer, 0, buffer.Length)) > 0)
                {
                    output.Write(buffer, 0, read);
                    done += read;
                    var percent = size > 0 ? (int)(done * 100 / size) : 0;
                    if (percent != last) { last = percent; worker.ReportProgress(percent, null); }
                }
            }

            worker.ReportProgress(-1, "Checking the download...");
            string actual;
            using (var sha = SHA256.Create())
            using (var stream = File.OpenRead(file))
                actual = BitConverter.ToString(sha.ComputeHash(stream)).Replace("-", "");
            if (!string.Equals(actual, sha256, StringComparison.OrdinalIgnoreCase))
            {
                File.Delete(file);
                throw new InvalidOperationException("The download was damaged. Please try again.");
            }

            worker.ReportProgress(-1, "Installing Vanity Player " + version + "...");
            var install = Process.Start(new ProcessStartInfo("msiexec.exe", "/i \"" + file + "\" /passive /norestart") { UseShellExecute = false });
            install.WaitForExit();
            try { File.Delete(file); } catch (Exception) { }
            // 0 = installed, 3010 = installed and Windows wants a restart, 1602 = cancelled.
            if (install.ExitCode == 1602) throw new OperationCanceledException("The installation was cancelled.");
            if (install.ExitCode != 0 && install.ExitCode != 3010)
                throw new InvalidOperationException("Windows couldn't install it (code " + install.ExitCode + ").");
            e.Result = version;
        }

        void Finished(object sender, RunWorkerCompletedEventArgs e)
        {
            bar.Visible = false;
            button.Visible = true;
            if (e.Error != null)
            {
                status.Text = e.Error is WebException ? "Couldn't download. Check your internet connection and try again." : e.Error.Message;
                SetButton("Try again", delegate { Start(); });
                return;
            }
            var exe = FindPlayer();
            if (exe == null)
            {
                status.Text = "Vanity Player " + e.Result + " is installed. Find it in the Start menu.";
                SetButton("Close", delegate { Close(); });
                return;
            }
            status.Text = "Vanity Player " + e.Result + " is installed. It keeps itself up to date from now on.";
            SetButton("Open", delegate
            {
                try { Process.Start(exe); } catch (Exception) { }
                Close();
            });
        }

        EventHandler current;

        void SetButton(string text, EventHandler onClick)
        {
            if (current != null) button.Click -= current;
            current = onClick;
            button.Text = text;
            button.Click += onClick;
            button.Focus();
        }

        static WebClient NewClient()
        {
            var web = new WebClient();
            web.Headers[HttpRequestHeader.UserAgent] = "VanityPlayer-Installer";
            return web;
        }

        static string FindPlayer()
        {
            var places = new[]
            {
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles),
                Environment.GetEnvironmentVariable("ProgramW6432") ?? "",
            };
            foreach (var place in places)
            {
                if (place.Length == 0) continue;
                var exe = Path.Combine(place, "VanityPlayer", "VanityPlayer.exe");
                if (File.Exists(exe)) return exe;
            }
            return null;
        }
    }
}
