using Microsoft.Win32;
using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Text.Json;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Interop;

using Path = System.IO.Path;

namespace BlockinLauncher
{
    public class LauncherSettings
    {
        public string GamePath { get; set; } = @"D:\Games\Blockin";
        public string SourcePath { get; set; } = @"D:\Games\Blockin Source";
        public string BuildPath { get; set; } = @"D:\Games\Blockin";
        public string NativesPath { get; set; } = @"D:\Games\Blockin\natives";
        public bool ShowConsole { get; set; } = true;
        public bool EnableDebug { get; set; } = false;
        public List<string> JarFiles { get; set; } = new List<string> { "lwjgl.jar", "lwjgl_util.jar" };
    }

    public partial class MainWindow : Window
    {
        public ObservableCollection<string> JarFiles { get; set; } = new ObservableCollection<string>();
        private readonly string settingsFilePath = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "launcher_settings.json");
        private bool isInitializing = true;

        // --- WIN32 INTEROP FOR RESIZING & TASKBAR BOUNDS ---
        [DllImport("user32.dll")]
        private static extern IntPtr SendMessage(IntPtr hWnd, int msg, IntPtr wParam, IntPtr lParam);

        [DllImport("user32.dll")]
        private static extern IntPtr MonitorFromWindow(IntPtr handle, uint flags);

        [DllImport("user32.dll")]
        private static extern bool GetMonitorInfo(IntPtr hMonitor, ref MONITORINFO lpmi);

        private const int WM_SYSCOMMAND = 0x0112;
        private const int SC_SIZE = 0xF000;
        private const uint MONITOR_DEFAULTTONEAREST = 0x00000002;

        [StructLayout(LayoutKind.Sequential)]
        private struct POINT
        {
            public int x;
            public int y;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct RECT
        {
            public int left;
            public int top;
            public int right;
            public int bottom;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct MONITORINFO
        {
            public int cbSize;
            public RECT rcMonitor;
            public RECT rcWork; // Working area excluding taskbar
            public uint dwFlags;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct MINMAXINFO
        {
            public POINT ptReserved;
            public POINT ptMaxSize;
            public POINT ptMaxPosition;
            public POINT ptMinTrackSize;
            public POINT ptMaxTrackSize;
        }

        private enum ResizeDirection
        {
            Left = 1,
            Right = 2,
            Top = 3,
            TopLeft = 4,
            TopRight = 5,
            Bottom = 6,
            BottomLeft = 7,
            BottomRight = 8
        }

        public MainWindow()
        {
            InitializeComponent();
            JarListBox.ItemsSource = JarFiles;

            LoadSettings();
            isInitializing = false;
        }

        // --- HOOK WINDOW MESSAGES FOR TASKBAR CONSTRAINT ---
        protected override void OnSourceInitialized(EventArgs e)
        {
            base.OnSourceInitialized(e);
            IntPtr handle = new WindowInteropHelper(this).Handle;
            HwndSource.FromHwnd(handle)?.AddHook(WindowProc);
        }

        private IntPtr WindowProc(IntPtr hwnd, int msg, IntPtr wParam, IntPtr lParam, ref bool handled)
        {
            const int WM_GETMINMAXINFO = 0x0024;

            if (msg == WM_GETMINMAXINFO)
            {
                WmGetMinMaxInfo(hwnd, lParam);
                handled = true;
            }

            return IntPtr.Zero;
        }

        private void WmGetMinMaxInfo(IntPtr hwnd, IntPtr lParam)
        {
            MINMAXINFO mmi = (MINMAXINFO)Marshal.PtrToStructure(lParam, typeof(MINMAXINFO))!;

            IntPtr monitor = MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST);
            if (monitor != IntPtr.Zero)
            {
                MONITORINFO monitorInfo = new MONITORINFO();
                monitorInfo.cbSize = Marshal.SizeOf(typeof(MONITORINFO));
                GetMonitorInfo(monitor, ref monitorInfo);

                RECT rcWorkArea = monitorInfo.rcWork;
                RECT rcMonitorArea = monitorInfo.rcMonitor;

                mmi.ptMaxPosition.x = Math.Abs(rcWorkArea.left - rcMonitorArea.left);
                mmi.ptMaxPosition.y = Math.Abs(rcWorkArea.top - rcMonitorArea.top);
                mmi.ptMaxSize.x = Math.Abs(rcWorkArea.right - rcWorkArea.left);
                mmi.ptMaxSize.y = Math.Abs(rcWorkArea.bottom - rcWorkArea.top);
            }

            Marshal.StructureToPtr(mmi, lParam, true);
        }

        // --- SETTINGS PERSISTENCE (JSON) ---
        private void LoadSettings()
        {
            try
            {
                if (File.Exists(settingsFilePath))
                {
                    string json = File.ReadAllText(settingsFilePath);
                    var settings = JsonSerializer.Deserialize<LauncherSettings>(json);

                    if (settings != null)
                    {
                        GamePathTxt.Text = settings.GamePath;
                        SourcePathTxt.Text = settings.SourcePath;
                        BuildPathTxt.Text = settings.BuildPath;
                        NativesPathTxt.Text = settings.NativesPath;
                        ShowConsoleChk.IsChecked = settings.ShowConsole;
                        EnableDebugChk.IsChecked = settings.EnableDebug;

                        JarFiles.Clear();
                        foreach (var jar in settings.JarFiles)
                        {
                            JarFiles.Add(jar);
                        }
                    }
                }
                else
                {
                    JarFiles.Add("lwjgl.jar");
                    JarFiles.Add("lwjgl_util.jar");
                }
            }
            catch (Exception ex)
            {
                LogOutput($"[WARNING] Failed to load settings: {ex.Message}");
            }
        }

        private void SaveSettings()
        {
            if (isInitializing) return;

            try
            {
                var settings = new LauncherSettings
                {
                    GamePath = GamePathTxt.Text,
                    SourcePath = SourcePathTxt.Text,
                    BuildPath = BuildPathTxt.Text,
                    NativesPath = NativesPathTxt.Text,
                    ShowConsole = ShowConsoleChk.IsChecked ?? true,
                    EnableDebug = EnableDebugChk.IsChecked ?? false,
                    JarFiles = JarFiles.ToList()
                };

                string json = JsonSerializer.Serialize(settings, new JsonSerializerOptions { WriteIndented = true });
                File.WriteAllText(settingsFilePath, json);
            }
            catch (Exception ex)
            {
                LogOutput($"[ERROR] Failed to save settings: {ex.Message}");
            }
        }

        protected override void OnClosed(EventArgs e)
        {
            SaveSettings();
            base.OnClosed(e);
        }

        // --- WINDOW MANAGEMENT & RESIZING ---
        private void TitleBar_MouseDown(object sender, MouseButtonEventArgs e)
        {
            if (e.ChangedButton == MouseButton.Left)
            {
                if (e.ClickCount == 2)
                {
                    ToggleMaximize();
                }
                else
                {
                    DragMove();
                }
            }
        }

        private void Minimize_Click(object sender, RoutedEventArgs e) => WindowState = WindowState.Minimized;
        private void Maximize_Click(object sender, RoutedEventArgs e) => ToggleMaximize();
        private void Close_Click(object sender, RoutedEventArgs e) => Close();

        private void ToggleMaximize()
        {
            if (WindowState == WindowState.Maximized)
            {
                WindowState = WindowState.Normal;
                MainContainer.CornerRadius = new CornerRadius(12);
                MaximizeBtn.Content = "🗖";
            }
            else
            {
                WindowState = WindowState.Maximized;
                MainContainer.CornerRadius = new CornerRadius(0);
                MaximizeBtn.Content = "🗗";
            }
        }

        private void Window_KeyDown(object sender, KeyEventArgs e)
        {
            if (e.Key == Key.F11)
            {
                ToggleMaximize();
                e.Handled = true;
            }
            else if (e.Key == Key.Escape && WindowState == WindowState.Maximized)
            {
                ToggleMaximize();
                e.Handled = true;
            }
        }

        private void Resize_Start(object sender, MouseButtonEventArgs e)
        {
            if (e.LeftButton == MouseButtonState.Pressed && sender is FrameworkElement element && element.Tag != null)
            {
                string? tag = element.Tag.ToString();
                if (!string.IsNullOrEmpty(tag) && Enum.TryParse<ResizeDirection>(tag, out var direction))
                {
                    IntPtr hwnd = new WindowInteropHelper(this).Handle;
                    SendMessage(hwnd, WM_SYSCOMMAND, (IntPtr)(SC_SIZE + (int)direction), IntPtr.Zero);
                }
            }
        }

        // --- LOGGING HELPERS ---
        private void LogOutput(string message)
        {
            Dispatcher.Invoke(() =>
            {
                ConsoleOutput.AppendText($"[{DateTime.Now:HH:mm:ss}] {message}\n");
                ConsoleScroll.ScrollToBottom();
            });
        }

        private void LogDebugOutput(string message)
        {
            Dispatcher.Invoke(() =>
            {
                DebugConsoleOutput.AppendText($"[{DateTime.Now:HH:mm:ss}] {message}\n");
                DebugConsoleScroll.ScrollToBottom();
            });
        }

        private string GetAbsolutePath(string path)
        {
            if (string.IsNullOrWhiteSpace(path)) return AppDomain.CurrentDomain.BaseDirectory;
            if (Path.IsPathRooted(path)) return Path.GetFullPath(path);
            return Path.GetFullPath(Path.Combine(AppDomain.CurrentDomain.BaseDirectory, path));
        }

        // --- BROWSE DIALOG HELPERS ---
        private string SelectFolder()
        {
            OpenFolderDialog dialog = new OpenFolderDialog
            {
                Multiselect = false
            };

            return dialog.ShowDialog() == true ? dialog.FolderName : string.Empty;
        }

        private void BrowseGamePath_Click(object sender, RoutedEventArgs e)
        {
            string folder = SelectFolder();
            if (!string.IsNullOrEmpty(folder))
            {
                GamePathTxt.Text = folder;
                SaveSettings();
            }
        }

        private void BrowseSourcePath_Click(object sender, RoutedEventArgs e)
        {
            string folder = SelectFolder();
            if (!string.IsNullOrEmpty(folder))
            {
                SourcePathTxt.Text = folder;
                SaveSettings();
            }
        }

        private void BrowseBuildPath_Click(object sender, RoutedEventArgs e)
        {
            string folder = SelectFolder();
            if (!string.IsNullOrEmpty(folder))
            {
                BuildPathTxt.Text = folder;
                SaveSettings();
            }
        }

        private void BrowseNativesPath_Click(object sender, RoutedEventArgs e)
        {
            string folder = SelectFolder();
            if (!string.IsNullOrEmpty(folder))
            {
                NativesPathTxt.Text = folder;
                SaveSettings();
            }
        }

        private void BrowseJarFile_Click(object sender, RoutedEventArgs e)
        {
            OpenFileDialog dialog = new OpenFileDialog
            {
                Filter = "Executable JAR Files (*.jar)|*.jar|All Files (*.*)|*.*",
                Multiselect = false
            };

            if (dialog.ShowDialog() == true)
            {
                JarInputTxt.Text = dialog.FileName;
            }
        }

        // --- NAVIGATION TOGGLES ---
        private void NavGame_Click(object sender, RoutedEventArgs e)
        {
            GameView.Visibility = Visibility.Visible;
            SettingsView.Visibility = Visibility.Collapsed;
            DebugView.Visibility = Visibility.Collapsed;
        }

        private void NavSettings_Click(object sender, RoutedEventArgs e)
        {
            GameView.Visibility = Visibility.Collapsed;
            SettingsView.Visibility = Visibility.Visible;
            DebugView.Visibility = Visibility.Collapsed;
        }

        private void NavDebug_Click(object sender, RoutedEventArgs e)
        {
            GameView.Visibility = Visibility.Collapsed;
            SettingsView.Visibility = Visibility.Collapsed;
            DebugView.Visibility = Visibility.Visible;
        }

        private void DebugChk_Changed(object sender, RoutedEventArgs e)
        {
            bool isDebug = EnableDebugChk.IsChecked ?? false;
            DebugNavBtn.Visibility = isDebug ? Visibility.Visible : Visibility.Collapsed;
            BuildBtn.Visibility = isDebug ? Visibility.Visible : Visibility.Collapsed;

            if (!isDebug && DebugView.Visibility == Visibility.Visible)
            {
                NavGame_Click(sender, e);
            }

            SaveSettings();
        }

        private void ConsoleChk_Changed(object sender, RoutedEventArgs e)
        {
            bool showConsole = ShowConsoleChk.IsChecked ?? true;
            GameConsoleContainer.Visibility = showConsole ? Visibility.Visible : Visibility.Collapsed;
            SaveSettings();
        }

        // --- JAR MANAGEMENT ---
        private void AddJar_Click(object sender, RoutedEventArgs e)
        {
            string jarName = JarInputTxt.Text.Trim();
            if (!string.IsNullOrEmpty(jarName) && !JarFiles.Contains(jarName))
            {
                JarFiles.Add(jarName);
                JarInputTxt.Clear();
                SaveSettings();
            }
        }

        private void RemoveJar_Click(object sender, RoutedEventArgs e)
        {
            if (JarListBox.SelectedItem is string selectedJar)
            {
                JarFiles.Remove(selectedJar);
                SaveSettings();
            }
        }

        // --- RESPONSE-FILE JAVAC BUILD LOGIC ---
        private async void BuildButton_Click(object sender, RoutedEventArgs e)
        {
            SaveSettings();
            StatusText.Text = "Status: Compiling Java Sources...";
            LogOutput("Starting compilation process...");
            LogDebugOutput("===============================================");
            LogDebugOutput("Starting Silent JAVAC Build via Response File...");

            string sourceDir = GetAbsolutePath(SourcePathTxt.Text);
            string buildDir = GetAbsolutePath(BuildPathTxt.Text);

            if (!Directory.Exists(sourceDir))
            {
                LogOutput($"[ERROR] Source directory does not exist: {sourceDir}");
                LogDebugOutput($"[ERROR] Source directory does not exist: {sourceDir}");
                StatusText.Text = "Status: Build Failed";
                return;
            }

            if (!Directory.Exists(buildDir))
            {
                Directory.CreateDirectory(buildDir);
                LogDebugOutput($"[SYSTEM] Created build output folder: {buildDir}");
            }

            string[] javaFiles = Directory.GetFiles(sourceDir, "*.java", SearchOption.AllDirectories);
            if (javaFiles.Length == 0)
            {
                LogOutput("[ERROR] No .java source files found to compile.");
                LogDebugOutput("[ERROR] No .java source files found in source folder.");
                StatusText.Text = "Status: Build Failed";
                return;
            }

            LogDebugOutput($"Found {javaFiles.Length} Java source file(s) to compile.");

            List<string> cpList = JarFiles.Select(j => GetAbsolutePath(j)).ToList();
            cpList.Add(buildDir);
            cpList.Add(".");
            string classpath = string.Join(";", cpList);

            string gameDir = GetAbsolutePath(GamePathTxt.Text);

            await Task.Run(() => ExecuteJavacWithResponseFile(buildDir, classpath, javaFiles, sourceDir, gameDir));
        }

        private void ExecuteJavacWithResponseFile(string outputDir, string classpath, string[] javaFiles, string sourceDir, string gameDir)
        {
            string tempSourcesFile = Path.Combine(Path.GetTempPath(), "blockin_sources.txt");

            try
            {
                File.WriteAllLines(tempSourcesFile, javaFiles.Select(f => $"\"{f.Replace("\\", "\\\\")}\""));

                string args = $"-d \"{outputDir}\" -cp \"{classpath}\" @\"{tempSourcesFile}\"";

                ProcessStartInfo psi = new ProcessStartInfo
                {
                    FileName = "javac.exe",
                    Arguments = args,
                    RedirectStandardOutput = true,
                    RedirectStandardError = true,
                    UseShellExecute = false,
                    CreateNoWindow = true
                };

                using (Process process = new Process { StartInfo = psi })
                {
                    process.OutputDataReceived += (s, e) => { if (!string.IsNullOrEmpty(e.Data)) LogDebugOutput(e.Data!); };
                    process.ErrorDataReceived += (s, e) => { if (!string.IsNullOrEmpty(e.Data)) LogDebugOutput($"[COMPILER] {e.Data}"); };

                    process.Start();
                    process.BeginOutputReadLine();
                    process.BeginErrorReadLine();
                    process.WaitForExit();

                    Dispatcher.Invoke(() =>
                    {
                        if (process.ExitCode == 0)
                        {
                            CopyResources(sourceDir, outputDir, gameDir);
                            StatusText.Text = "Status: Build Successful";
                            LogOutput($"[SUCCESS] Class files generated at: {outputDir}");
                            LogDebugOutput($"[SUCCESS] All .class files written strictly to: {outputDir}");
                        }
                        else
                        {
                            StatusText.Text = "Status: Build Failed";
                            LogOutput($"[FAILED] javac process exited with code {process.ExitCode}");
                            LogDebugOutput($"[BUILD FAILED] Exit Code: {process.ExitCode}");
                        }
                    });
                }
            }
            catch (Exception ex)
            {
                Dispatcher.Invoke(() =>
                {
                    StatusText.Text = "Status: Build Exception";
                    LogOutput($"[ERROR] Javac execution error: {ex.Message}");
                    LogDebugOutput($"[ERROR] {ex.Message}");
                });
            }
            finally
            {
                if (File.Exists(tempSourcesFile))
                {
                    try { File.Delete(tempSourcesFile); } catch { }
                }
            }
        }

        // --- RESOURCE STAGING (PNGs + Mods copied to the game/build dirs) ---
        private void CopyResources(string sourceDir, string buildDir, string gameDir)
        {
            try
            {
                foreach (string file in Directory.GetFiles(sourceDir, "*.png", SearchOption.AllDirectories))
                {
                    string rel = Path.GetRelativePath(sourceDir, file);
                    string dest = Path.Combine(buildDir, rel);
                    string? dir = Path.GetDirectoryName(dest);
                    if (dir != null) Directory.CreateDirectory(dir);
                    File.Copy(file, dest, true);
                }

                string srcMods = Path.Combine(sourceDir, "Mods");
                string dstMods = Path.Combine(gameDir, "Mods");
                if (Directory.Exists(srcMods)) CopyDirectory(srcMods, dstMods);

                LogOutput($"[RESOURCES] Copied textures and Mods into {buildDir} / {gameDir}");
                LogDebugOutput("[RESOURCES] Texture/Mod staging complete.");
            }
            catch (Exception ex)
            {
                LogOutput($"[WARNING] Resource copy failed: {ex.Message}");
            }
        }

        private static void CopyDirectory(string src, string dst)
        {
            Directory.CreateDirectory(dst);
            foreach (string dir in Directory.GetDirectories(src))
            {
                CopyDirectory(dir, Path.Combine(dst, Path.GetFileName(dir)));
            }
            foreach (string file in Directory.GetFiles(src))
            {
                File.Copy(file, Path.Combine(dst, Path.GetFileName(file)), true);
            }
        }

        // --- RUN GAME LOGIC ---
        private void RunButton_Click(object sender, RoutedEventArgs e)
        {
            SaveSettings();
            string gameDir = GetAbsolutePath(GamePathTxt.Text);
            string buildDir = GetAbsolutePath(BuildPathTxt.Text);

            LogOutput("Launching Blockin...");

            string classpath = $"\"{buildDir}\";" + string.Join(";", JarFiles) + ";.";
            string nativesPath = NativesPathTxt.Text.Trim();

            string jvmArgs = $"--enable-native-access=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED -cp {classpath} -Djava.library.path=\"{nativesPath}\" com.insanestudios.blockin.Blockin";

            ProcessStartInfo javaRunner = new ProcessStartInfo
            {
                FileName = "java.exe",
                Arguments = jvmArgs,
                WorkingDirectory = gameDir,
                UseShellExecute = false,
                CreateNoWindow = true
            };

            try
            {
                Process.Start(javaRunner);
                StatusText.Text = "Status: Game Running";
                LogOutput("Successfully started Blockin process.");
            }
            catch (Exception ex)
            {
                LogOutput($"[ERROR] Could not start Java runtime: {ex.Message}");
                StatusText.Text = "Status: Launch Failed";
            }
        }
    }
}