using System;
using System.Collections.Generic;
using System.IO;
using System.Text.RegularExpressions;
using Microsoft.Win32;

namespace Rwml.Setup
{
    /// <summary>Finds Rusted Warfare installations without asking the user first.</summary>
    internal static class GameLocator
    {
        internal const string FolderName = "Rusted Warfare";

        private static readonly StringComparison IgnoreCase = StringComparison.OrdinalIgnoreCase;

        internal static IReadOnlyList<string> Candidates()
        {
            var found = new List<string>();

            foreach (var steam in SteamRoots())
            {
                Add(found, Path.Combine(steam, "steamapps", "common", FolderName));
                foreach (var library in Libraries(steam))
                {
                    Add(found, Path.Combine(library, "steamapps", "common", FolderName));
                }
            }

            foreach (var location in UninstallLocations())
            {
                Add(found, location);
            }

            foreach (var guess in CommonPaths())
            {
                Add(found, guess);
            }

            return found;
        }

        /// <summary>Returns the first directory that looks like a complete installation.</summary>
        internal static string Detect()
        {
            foreach (var candidate in Candidates())
            {
                if (IsGameDirectory(candidate))
                {
                    return candidate;
                }
            }
            return null;
        }

        /// <summary>A directory qualifies when the engine jar and the game executable are both present.</summary>
        internal static bool IsGameDirectory(string path)
        {
            if (string.IsNullOrWhiteSpace(path) || !Directory.Exists(path))
            {
                return false;
            }

            var hasJar = File.Exists(Path.Combine(path, "game-lib.jar"));
            var hasExecutable = File.Exists(Path.Combine(path, "Rusted Warfare.exe"))
                || File.Exists(Path.Combine(path, "Rusted Warfare - 64.exe"));
            return hasJar && hasExecutable;
        }

        private static IEnumerable<string> SteamRoots()
        {
            var roots = new List<string>();
            AddRoot(roots, ReadValue(Registry.CurrentUser, @"Software\Valve\Steam", "SteamPath"));
            AddRoot(roots, ReadValue(Registry.LocalMachine, @"SOFTWARE\WOW6432Node\Valve\Steam", "InstallPath"));
            AddRoot(roots, ReadValue(Registry.LocalMachine, @"SOFTWARE\Valve\Steam", "InstallPath"));

            var programFilesX86 = Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86);
            if (!string.IsNullOrEmpty(programFilesX86))
            {
                AddRoot(roots, Path.Combine(programFilesX86, "Steam"));
            }
            var programFiles = Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles);
            if (!string.IsNullOrEmpty(programFiles))
            {
                AddRoot(roots, Path.Combine(programFiles, "Steam"));
            }
            return roots;
        }

        private static void AddRoot(List<string> roots, string path)
        {
            if (string.IsNullOrWhiteSpace(path) || !Directory.Exists(path))
            {
                return;
            }
            var normalized = path.TrimEnd('\\', '/');
            if (!roots.Exists(existing => string.Equals(existing, normalized, IgnoreCase)))
            {
                roots.Add(normalized);
            }
        }

        private static IEnumerable<string> Libraries(string steamRoot)
        {
            var libraries = new List<string> { steamRoot };
            var manifest = Path.Combine(steamRoot, "steamapps", "libraryfolders.vdf");
            if (File.Exists(manifest))
            {
                var text = File.ReadAllText(manifest);
                foreach (Match match in Regex.Matches(text, "\"path\"\\s+\"(?<path>[^\"]+)\""))
                {
                    var library = match.Groups["path"].Value.Replace("\\\\", "\\");
                    if (Directory.Exists(library)
                        && !libraries.Exists(existing => string.Equals(existing, library, IgnoreCase)))
                    {
                        libraries.Add(library);
                    }
                }
            }
            return libraries;
        }

        private static IEnumerable<string> UninstallLocations()
        {
            var locations = new List<string>();
            var keys = new[]
            {
                @"SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall",
                @"SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall",
            };

            foreach (var hive in new[] { Registry.LocalMachine, Registry.CurrentUser })
            {
                foreach (var keyPath in keys)
                {
                    try
                    {
                        using (var root = hive.OpenSubKey(keyPath))
                        {
                            if (root == null)
                            {
                                continue;
                            }
                            foreach (var name in root.GetSubKeyNames())
                            {
                                using (var entry = root.OpenSubKey(name))
                                {
                                    var display = entry?.GetValue("DisplayName") as string;
                                    var location = entry?.GetValue("InstallLocation") as string;
                                    if (display != null
                                        && display.IndexOf(FolderName, StringComparison.OrdinalIgnoreCase) >= 0
                                        && IsGameDirectory(location))
                                    {
                                        locations.Add(location.TrimEnd('\\', '/'));
                                    }
                                }
                            }
                        }
                    }
                    catch (Exception)
                    {
                        // A locked or malformed registry key must not hide the other locations.
                    }
                }
            }
            return locations;
        }

        private static IEnumerable<string> CommonPaths()
        {
            var bases = new[]
            {
                Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86),
                Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles),
            };
            foreach (var root in bases)
            {
                if (!string.IsNullOrEmpty(root))
                {
                    yield return Path.Combine(root, "Steam", "steamapps", "common", FolderName);
                }
            }
        }

        private static string ReadValue(RegistryKey hive, string keyPath, string name)
        {
            try
            {
                using (var key = hive.OpenSubKey(keyPath))
                {
                    return key?.GetValue(name) as string;
                }
            }
            catch (Exception)
            {
                return null;
            }
        }

        private static void Add(List<string> target, string path)
        {
            if (string.IsNullOrWhiteSpace(path))
            {
                return;
            }
            var normalized = path.Replace('/', '\\').TrimEnd('\\');
            if (!target.Exists(existing => string.Equals(existing, normalized, IgnoreCase)))
            {
                target.Add(normalized);
            }
        }
    }
}
