// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.diagnostic;

import com.intellij.ide.IdeCoreBundle;
import com.intellij.openapi.application.ApplicationNamesInfo;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.*;
import com.intellij.openapi.util.io.NioFiles;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.util.SmartList;
import com.intellij.util.system.CpuArch;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.PropertyKey;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;

import static com.intellij.openapi.util.Pair.pair;

public final class VMOptions {
  private static final Logger LOG = Logger.getInstance(VMOptions.class);

  private static final NotNullLazyValue<Charset> JNU_CHARSET = NotNullLazyValue.createValue(() -> {
    try {
      return Charset.forName(System.getProperty("sun.jnu.encoding"));
    }
    catch (Exception e) {
      LOG.info(e);
      return Charset.defaultCharset();
    }
  });

  public enum MemoryKind {
    HEAP("Xmx", "", "change.memory.max.heap"),
    MIN_HEAP("Xms", "", "change.memory.min.heap"),
    METASPACE("XX:MaxMetaspaceSize", "=", "change.memory.metaspace"),
    CODE_CACHE("XX:ReservedCodeCacheSize", "=", "change.memory.code.cache");

    public final @NlsSafe String optionName;
    public final String option;
    private final String labelKey;

    MemoryKind(String name, String separator, @PropertyKey(resourceBundle = "messages.IdeCoreBundle") String key) {
      optionName = name;
      option = '-' + name + separator;
      labelKey = key;
    }

    public @NlsContexts.Label String label() {
      return IdeCoreBundle.message(labelKey);
    }
  }

  /**
   * Returns a value of the given {@link MemoryKind memory setting} (in MiBs), or {@code -1} when unable to find out
   * (e.g. a user doesn't have custom memory settings).
   *
   * @see #readOption(String, boolean)
   */
  public static int readOption(@NotNull MemoryKind kind, boolean effective) {
    String strValue = readOption(kind.option, effective);
    if (strValue != null) {
      try {
        return (int)(parseMemoryOption(strValue) >> 20);
      }
      catch (IllegalArgumentException e) {
        LOG.info(e);
      }
    }
    return -1;
  }

  /**
   * Returns a value of the given option, or {@code null} when unable to find.
   *
   * @param effective when {@code true}, the method returns a value for the current JVM (from {@link ManagementFactory#getRuntimeMXBean()}),
   *                  otherwise it reads a user's .vmoptions {@link #getWriteFile file}.
   */
  public static @Nullable String readOption(@NotNull String prefix, boolean effective) {
    List<String> lines = options(effective);
    for (int i = lines.size() - 1; i >= 0; i--) {
      String line = lines.get(i).trim();
      if (line.startsWith(prefix)) {
        return line.substring(prefix.length());
      }
    }
    return null;
  }

  /**
   * Returns a (possibly empty) list of values of the given option.
   *
   * @see #readOption(String, boolean)
   */
  public static @NotNull List<String> readOptions(@NotNull String prefix, boolean effective) {
    List<String> lines = options(effective), values = new SmartList<>();
    for (String s : lines) {
      String line = s.trim();
      if (line.startsWith(prefix)) {
        values.add(line.substring(prefix.length()));
      }
    }
    return values;
  }

  private static List<String> options(boolean effective) {
    if (effective) {
      return ManagementFactory.getRuntimeMXBean().getInputArguments();
    }
    else {
      Path file = getWriteFile();
      if (file != null && Files.exists(file)) {
        try {
          return Files.readAllLines(file, getFileCharset());
        }
        catch (IOException e) {
          LOG.warn(e);
        }
      }
    }
    return List.of();
  }

  /**
   * Parses a Java VM memory option (such as "-Xmx") string value and returns its numeric value, in bytes.
   * See <a href="https://docs.oracle.com/en/java/javase/16/docs/specs/man/java.html#extra-options-for-java">'java' command manual</a>
   * for the syntax.
   *
   * @throws IllegalArgumentException when either a number or a unit is invalid
   */
  public static long parseMemoryOption(@NotNull String strValue) throws IllegalArgumentException {
    int p = 0;
    while (p < strValue.length() && StringUtil.isDecimalDigit(strValue.charAt(p))) p++;
    long numValue = Long.parseLong(strValue.substring(0, p));
    if (p < strValue.length()) {
      String unit = strValue.substring(p);
      if ("k".equalsIgnoreCase(unit)) numValue <<= 10;
      else if ("m".equalsIgnoreCase(unit)) numValue <<= 20;
      else if ("g".equalsIgnoreCase(unit)) numValue <<= 30;
      else throw new IllegalArgumentException("Invalid unit: " + unit);
    }
    return numValue;
  }

  /**
   * Sets or deletes a Java memory limit (in MiBs). See {@link #setOption(String, String)} for details.
   */
  public static void setOption(@NotNull MemoryKind option, int value) throws IOException {
    setOption(option.option, value > 0 ? value + "m" : null);
  }

  /**
   * Sets or deletes a Java system property. See {@link #setOption(String, String)} for details.
   */
  public static void setProperty(@NotNull String name, @Nullable String newValue) throws IOException {
    setOption("-D" + name + '=', newValue);
  }

  /**
   * <p>Sets or deletes a VM option in a user's .vmoptions {@link #getWriteFile file}.</p>
   *
   * <p>When {@code newValue} is {@code null}, all options that start with a given prefix are removed from the file.
   * When {@code newValue} is not {@code null} and an option is present in the file, it's value is replaced, otherwise
   * the option is added to the file.</p>
   */
  public static void setOption(@NotNull String prefix, @Nullable String newValue) throws IOException {
    setOptions(List.of(pair(prefix, newValue)));
  }

  /**
   * Sets or deletes multiple options in one pass. See {@link #setOption(String, String)} for details.
   */
  public static void setOptions(@NotNull List<Pair<@NotNull String, @Nullable String>> _options) throws IOException {
    Path file = getWriteFile();
    if (file == null) {
      throw new IOException("The IDE is not configured for using custom VM options (jb.vmOptionsFile=" + System.getProperty("jb.vmOptionsFile") + ")");
    }

    List<String> lines = Files.exists(file) ? new ArrayList<>(Files.readAllLines(file, getFileCharset())) : new ArrayList<>();
    List<Pair<String, @Nullable String>> options = new ArrayList<>(_options);
    boolean modified = false;

    for (ListIterator<String> il = lines.listIterator(lines.size()); il.hasPrevious(); ) {
      String line = il.previous().trim();
      for (Iterator<Pair<String, String>> io = options.iterator(); io.hasNext(); ) {
        Pair<String, String> option = io.next();
        if (line.startsWith(option.first)) {
          if (option.second == null) {
            il.remove();
            modified = true;
          }
          else {
            String newLine = option.first + option.second;
            if (!newLine.equals(line)) {
              il.set(newLine);
              modified = true;
            }
            io.remove();
          }
          break;
        }
      }
    }

    for (Pair<String, String> option : options) {
      if (option.second != null) {
        lines.add(option.first + option.second);
        modified = true;
      }
    }

    if (modified) {
      NioFiles.createDirectories(file.getParent());
      Files.write(file, lines, getFileCharset());
    }
  }

  public static boolean canWriteOptions() {
    return getWriteFile() != null;
  }

  @Nullable
  public static String read() {
    try {
      Path newFile = getWriteFile();
      if (newFile != null && Files.exists(newFile)) {
        return Files.readString(newFile, getFileCharset());
      }

      String vmOptionsFile = System.getProperty("jb.vmOptionsFile");
      if (vmOptionsFile != null) {
        return Files.readString(Path.of(vmOptionsFile), getFileCharset());
      }
    }
    catch (IOException e) {
      LOG.info(e);
    }

    return null;
  }

  public static @Nullable Path getWriteFile() {
    String vmOptionsFile = System.getProperty("jb.vmOptionsFile");
    if (vmOptionsFile == null) {
      // launchers should specify a path to a VM options file used to configure a JVM
      return null;
    }

    vmOptionsFile = new File(vmOptionsFile).getAbsolutePath();
    if (!PathManager.isUnderHomeDirectory(vmOptionsFile)) {
      // a file is located outside the IDE installation - meaning it is safe to overwrite
      return Path.of(vmOptionsFile);
    }

    String location = PathManager.getCustomOptionsDirectory();
    if (location == null) {
      return null;
    }

    return Path.of(location, getCustomVMOptionsFileName());
  }

  @NotNull
  public static String getCustomVMOptionsFileName() {
    String fileName = ApplicationNamesInfo.getInstance().getScriptName();
    if (!SystemInfo.isMac && CpuArch.isIntel64()) fileName += "64";
    if (SystemInfo.isWindows) fileName += ".exe";
    fileName += ".vmoptions";
    return fileName;
  }

  /**
   * In general, clients should abstain from direct reading or modification of a user's .vmoptions {@link #getWriteFile file},
   * but when unavoidable, this charset must be used for reading and writing the file.
   */
  public static @NotNull Charset getFileCharset() {
    return JNU_CHARSET.getValue();
  }

  //<editor-fold desc="Deprecated stuff.">
  /** @deprecated ignores write errors; please use {@link #setOption(MemoryKind, int)} instead */
  @Deprecated(forRemoval = true)
  @ApiStatus.ScheduledForRemoval(inVersion = "2022.2")
  @SuppressWarnings("DeprecatedIsStillUsed")
  public static void writeOption(@NotNull MemoryKind option, int value) {
    try {
      setOption(option.option, value + "m");
    }
    catch (IOException e) {
      LOG.warn(e);
    }
  }

  /** @deprecated ignores write errors; please use {@link #setProperty} instead */
  @Deprecated(forRemoval = true)
  @ApiStatus.ScheduledForRemoval(inVersion = "2022.2")
  public static void writeOption(@NotNull String option, @NotNull String separator, @NotNull String value) {
    try {
      setOption("-D" + option + separator, value);
    }
    catch (IOException e) {
      LOG.warn(e);
    }
  }
  //</editor-fold>
}
