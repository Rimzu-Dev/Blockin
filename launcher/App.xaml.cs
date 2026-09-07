using System;
using System.IO;
using System.Windows;

namespace BlockinLauncher
{
    public partial class App : Application
    {
        protected override void OnStartup(StartupEventArgs e)
        {
            // Catch UI Thread Exceptions
            this.DispatcherUnhandledException += (sender, args) =>
            {
                ShowErrorAndLog("UI Exception", args.Exception);
                args.Handled = true;
            };

            // Catch Non-UI Thread Exceptions
            AppDomain.CurrentDomain.UnhandledException += (sender, args) =>
            {
                if (args.ExceptionObject is Exception ex)
                {
                    ShowErrorAndLog("Domain Exception", ex);
                }
            };

            base.OnStartup(e);
        }

        private void ShowErrorAndLog(string type, Exception ex)
        {
            string logMessage = $"[{DateTime.Now}] {type}: {ex.Message}\n\nStack Trace:\n{ex.StackTrace}";
            
            // Save log to project folder
            File.WriteAllText("crash_log.txt", logMessage);

            // Force display error dialog
            MessageBox.Show(logMessage, "Launcher Startup Crash", MessageBoxButton.OK, MessageBoxImage.Error);
        }
    }
}