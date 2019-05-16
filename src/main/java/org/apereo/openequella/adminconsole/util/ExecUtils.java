/**
 *     Licensed to The Apereo Foundation under one or more contributor license
 *     agreements. See the NOTICE file distributed with this work for additional
 *     information regarding copyright ownership.
 *
 *     The Apereo Foundation licenses this file to you under the Apache License,
 *     Version 2.0, (the "License"); you may not use this file except in compliance
 *     with the License. You may obtain a copy of the License at:
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 */
package org.apereo.openequella.adminconsole.util;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLDecoder;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ExecUtils {
  public static final String PLATFORM_SOLARIS_X86 = "solaris-x86";
  public static final String PLATFORM_SOLARIS_SPARC = "solaris-sparc";
  public static final String PLATFORM_SOLARIS64 = "solaris64";
  public static final String PLATFORM_LINUX = "linux";
  public static final String PLATFORM_LINUX64 = "linux64";
  public static final String PLATFORM_WIN = "win";
  public static final String PLATFORM_WIN32 = "win32";
  public static final String PLATFORM_WIN64 = "win64";
  public static final String PLATFORM_MAC = "mac";

  private static final Logger LOGGER = LoggerFactory.getLogger(ExecUtils.class);
  private static final String[] EXE_TYPES = {
    "", ".exe", ".bat"
  };

  public static File findExe(File file) {
    return findExe(file.getParentFile(), file.getName());
  }

  public static File findExe(File path, String exe) {
    for (String exeType : EXE_TYPES) {
      File exeFile = new File(path, exe + exeType);
      if (exeFile.exists() && exeFile.canExecute()) {
        return exeFile;
      }
    }
    return null;
  }

  public static ExecResult exec(List<String> options) {
    return exec(options.toArray(new String[options.size()]));
  }

  public static ExecResult exec(String... command) {
    return exec(command, null, null);
  }

  public static ExecResult exec(String command, String[] env, File dir) {
    Map<String, String> envMap = new HashMap<String, String>();
    for (String e : env) {
      StringTokenizer st2 = new StringTokenizer(e, "=");
      if (st2.hasMoreTokens()) {
        String key = st2.nextToken();
        if (st2.hasMoreTokens()) {
          envMap.put(key, st2.nextToken());
        }
      }
    }
    return exec(splitCommand(command), envMap, dir);
  }

  public static ExecResult exec(String[] cmdarray, Map<String, String> additionalEnv, File dir) {
    try {
      final Triple<Process, StreamReader, StreamReader> cp =
          createProcess(cmdarray, additionalEnv, dir);

      final Process proc = cp.getFirst();
      final StreamReader stdOut = cp.getSecond();
      final StreamReader stdErr = cp.getThird();

      int exitStatus;
      while (true) {
        synchronized (proc) {
          exitStatus = proc.waitFor();
          if (stdOut.isFinished() && stdErr.isFinished()) {
            break;
          }
          proc.wait();
        }
      }

      LOGGER.info("Exec finished with status " + exitStatus);
      return new ExecResult(exitStatus, stdOut.getResult(), stdErr.getResult());
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public static void execAsync(String cmd) {
    execAsync(splitCommand(cmd), null, null);
  }

  public static void execAsync(String[] cmdarray, Map<String, String> additionalEnv, File dir) {
    try {
      createProcess(cmdarray, additionalEnv, dir);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private static Triple<Process, StreamReader, StreamReader> createProcess(
      String[] cmdarray, Map<String, String> additionalEnv, File dir) throws IOException {

    if (LOGGER.isDebugEnabled()) {
      LOGGER.debug("Exec " + Arrays.asList(cmdarray));
    }

    ProcessBuilder pbuilder = new ProcessBuilder(cmdarray);
    if (additionalEnv != null) {
      Map<String, String> env = pbuilder.environment();
      env.putAll(additionalEnv);
    }
    pbuilder.directory(dir);
    
    Process proc = pbuilder.start();
    proc.getOutputStream().close();

    final InputStreamReader inErr = new InputStreamReader(proc.getErrorStream());
    final InputStreamReader inStd = new InputStreamReader(proc.getInputStream());

    StreamReader errReader = new StreamReader(inErr, proc);
    StreamReader outReader = new StreamReader(inStd, proc);
    errReader.start();
    outReader.start();

    return new Triple<Process, StreamReader, StreamReader>(proc, outReader, errReader);
  }

  public static String[] splitCommand(String cmd) {
    StringTokenizer st = new StringTokenizer(cmd);
    String[] cmdarray = new String[st.countTokens()];
    for (int i = 0; st.hasMoreTokens(); i++) {
      cmdarray[i] = st.nextToken();
    }
    return cmdarray;
  }

  public static class StreamReader extends Thread {
    private final StringBuilder writer = new StringBuilder();

    private final InputStreamReader reader;
    private final Process proc;

    private boolean finished;

    public StreamReader(InputStreamReader reader, Process proc) {
      this.reader = reader;
      this.proc = proc;
    }

    @Override
    public void run() {
      try (InputStreamReader rdr = reader) {
        StreamUtils.copyStream(rdr, writer);
      } catch (Exception ex) {
        LOGGER.error("Error reading from stream", ex);
        // nothing really you can do about this is there?
      } finally {
        finished = true;
        synchronized (proc) {
          proc.notify();
        }
      }
    }

    public boolean isFinished() {
      return finished;
    }

    public String getResult() {
      return writer.toString();
    }
  }

  public static class ExecResult {
    private final int exitStatus;
    private final String stdout;
    private final String stderr;

    public ExecResult(final int exitStatus, final String stdout, final String stderr) {
      this.exitStatus = exitStatus;
      this.stdout = stdout;
      this.stderr = stderr;
    }

    public void ensureOk() {
      if (exitStatus != 0) {
        throw new RuntimeException(
            "Exec process returned "
                + exitStatus
                + ".  StdOut:\n"
                + stdout
                + "\nStdErr:\n"
                + stderr);
      }
    }

    public int getExitStatus() {
      return exitStatus;
    }

    public String getStderr() {
      return stderr;
    }

    public String getStdout() {
      return stdout;
    }
  }

  public static boolean isPlatformUnix(String platform) {
    return !platform.startsWith("win");
  }

  
  public static String determinePlatform() {
    String name = System.getProperty("os.name").toLowerCase();
    boolean is64bit = is64Bit(name);
    if (name.startsWith("windows")) {
      return is64bit ? PLATFORM_WIN64 : PLATFORM_WIN32;
    } else if (name.startsWith(PLATFORM_LINUX)) {
      return is64bit ? PLATFORM_LINUX64 : PLATFORM_LINUX;
    } else if (name.startsWith("solaris") || name.startsWith("sunos")) {
      if (System.getProperty("os.arch").startsWith("sparc")) {
        return PLATFORM_SOLARIS_SPARC;
      } else {
        return is64bit ? PLATFORM_SOLARIS64 : PLATFORM_SOLARIS_X86;
      }
    } else if (name.startsWith("mac os x")) {
      return "mac";
    } else {
      return "unsupported";
    }
  }

  
  public static boolean is64Bit(String name) {
    boolean is64bit = false;
    if (name.contains("windows")) {
      is64bit = (System.getenv("ProgramFiles(x86)") != null);
    } else {
      is64bit = (System.getProperty("os.arch").indexOf("64") != -1);
    }
    return is64bit;
  }

  private ExecUtils() {
    throw new Error();
  }

  
  public static boolean isRunningInJar(Class<?> clazz) {
    String classFile = clazz.getName().replace('.', '/');
    URL res = clazz.getClassLoader().getResource(classFile + ".class");
    return res.getProtocol().equals("jar");
  }

  
  public static File findJarFolder(Class<?> clazz) {
    String classFile = clazz.getName().replace('.', '/');
    URL res = clazz.getClassLoader().getResource(classFile + ".class");
    if (!res.getProtocol().equals("jar")) {
      throw new RuntimeException("Not running from a jar file: '" + res + "'");
    }
    try {
      String file = res.getFile();
      URL fileUrl = new URL(file.substring(0, file.lastIndexOf('!')));
      return new File(URLDecoder.decode(fileUrl.getFile(), "UTF-8")).getParentFile();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
