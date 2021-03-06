/*
 *
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package org.apache.metron.stellar.common.shell;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.base.Splitter;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.PosixParser;
import org.apache.commons.lang3.StringUtils;
import org.apache.metron.stellar.dsl.Context;
import org.apache.metron.stellar.dsl.StellarFunctionInfo;
import org.apache.metron.stellar.common.StellarAssignment;
import org.apache.metron.stellar.common.utils.JSONUtils;
import org.jboss.aesh.complete.CompleteOperation;
import org.jboss.aesh.complete.Completion;
import org.jboss.aesh.console.AeshConsoleCallback;
import org.jboss.aesh.console.Console;
import org.jboss.aesh.console.ConsoleOperation;
import org.jboss.aesh.console.Prompt;
import org.jboss.aesh.console.settings.SettingsBuilder;
import org.jboss.aesh.terminal.CharacterType;
import org.jboss.aesh.terminal.Color;
import org.jboss.aesh.terminal.TerminalCharacter;
import org.jboss.aesh.terminal.TerminalColor;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * A REPL environment for Stellar.
 *
 * Useful for debugging Stellar expressions.
 */
public class StellarShell extends AeshConsoleCallback implements Completion {

  private static final String WELCOME = "Stellar, Go!\n" +
          "Please note that functions are loading lazily in the background and will be unavailable until loaded fully.";
  private List<TerminalCharacter> EXPRESSION_PROMPT = new ArrayList<TerminalCharacter>()
  {{
    add(new TerminalCharacter('[', new TerminalColor(Color.RED, Color.DEFAULT)));
    add(new TerminalCharacter('S', new TerminalColor(Color.GREEN, Color.DEFAULT), CharacterType.BOLD));
    add(new TerminalCharacter('t', new TerminalColor(Color.GREEN, Color.DEFAULT), CharacterType.BOLD));
    add(new TerminalCharacter('e', new TerminalColor(Color.GREEN, Color.DEFAULT), CharacterType.BOLD));
    add(new TerminalCharacter('l', new TerminalColor(Color.GREEN, Color.DEFAULT), CharacterType.BOLD));
    add(new TerminalCharacter('l', new TerminalColor(Color.GREEN, Color.DEFAULT), CharacterType.BOLD));
    add(new TerminalCharacter('a', new TerminalColor(Color.GREEN, Color.DEFAULT), CharacterType.BOLD));
    add(new TerminalCharacter('r', new TerminalColor(Color.GREEN, Color.DEFAULT), CharacterType.BOLD));
    add(new TerminalCharacter(']', new TerminalColor(Color.RED, Color.DEFAULT)));
    add(new TerminalCharacter('>', new TerminalColor(Color.GREEN, Color.DEFAULT), CharacterType.UNDERLINE));
    add(new TerminalCharacter('>', new TerminalColor(Color.GREEN, Color.DEFAULT), CharacterType.UNDERLINE));
    add(new TerminalCharacter('>', new TerminalColor(Color.GREEN, Color.DEFAULT), CharacterType.UNDERLINE));
    add(new TerminalCharacter(' ', new TerminalColor(Color.DEFAULT, Color.DEFAULT)));
  }};

  public static final String ERROR_PROMPT = "[!] ";
  public static final String MAGIC_PREFIX = "%";
  public static final String MAGIC_FUNCTIONS = MAGIC_PREFIX + "functions";
  public static final String MAGIC_VARS = MAGIC_PREFIX + "vars";
  public static final String DOC_PREFIX = "?";
  public static final String STELLAR_PROPERTIES_FILENAME = "stellar.properties";

  private StellarExecutor executor;

  private Console console;

  /**
   * Execute the Stellar REPL.
   */
  public static void main(String[] args) throws Exception {
    StellarShell shell = new StellarShell(args);
    shell.execute();
  }

  /**
   * Create a Stellar REPL.
   * @param args The commmand-line arguments.
   */
  public StellarShell(String[] args) throws Exception {

    // define valid command-line options
    Options options = new Options();
    options.addOption("z", "zookeeper", true, "Zookeeper URL fragment in the form [HOSTNAME|IPADDRESS]:PORT");
    options.addOption("v", "variables", true, "File containing a JSON Map of variables");
    options.addOption("irc", "inputrc", true, "File containing the inputrc if not the default ~/.inputrc");
    options.addOption("na", "no_ansi", false, "Make the input prompt not use ANSI colors.");
    options.addOption("h", "help", false, "Print help");
    options.addOption("p", "properties", true, "File containing Stellar properties");

    CommandLineParser parser = new PosixParser();
    CommandLine commandLine = parser.parse(options, args);

    // print help
    if(commandLine.hasOption("h")) {
      HelpFormatter formatter = new HelpFormatter();
      formatter.printHelp("stellar", options);
      System.exit(0);
    }

    try {
      StellarShellOptionsValidator.validateOptions(commandLine);
    }catch(IllegalArgumentException e){
      System.out.println(e.getMessage());
      System.exit(1);
    }
    
    console = createConsole(commandLine);
    executor = createExecutor(commandLine, console, getStellarProperties(commandLine));
    loadVariables(commandLine, executor);
    console.setPrompt(new Prompt(EXPRESSION_PROMPT));
    console.addCompletion(this);
    console.setConsoleCallback(this);
  }

  /**
   * Loads any variables defined in an external file.
   * @param commandLine The command line arguments.
   * @param executor The stellar executor.
   * @throws IOException
   */
  private static void loadVariables(CommandLine commandLine, StellarExecutor executor) throws IOException {
    if(commandLine.hasOption("v")) {

      Map<String, Object> variables = JSONUtils.INSTANCE.load(
              new File(commandLine.getOptionValue("v")),
              new TypeReference<Map<String, Object>>() {});

      for(Map.Entry<String, Object> kv : variables.entrySet()) {
        executor.assign(kv.getKey(), null, kv.getValue());
      }
    }
  }

  /**
   * Creates the Stellar execution environment.
   * @param commandLine The command line arguments.
   * @param console The console which drives the REPL.
   * @param properties Stellar properties.
   */
  private static StellarExecutor createExecutor(CommandLine commandLine, Console console, Properties properties) throws Exception {
    StellarExecutor executor;

    // create the executor
    if(commandLine.hasOption("z")) {
      String zookeeperUrl = commandLine.getOptionValue("z");
      executor = new StellarExecutor(zookeeperUrl, console, properties);

    } else {
      executor = new StellarExecutor(console, properties);
    }

    return executor;
  }

  /**
   * Creates the REPL's console.
   * @param commandLine The command line options.
   */
  private Console createConsole(CommandLine commandLine) {

    // console settings
    boolean useAnsi = !commandLine.hasOption("na");
    SettingsBuilder settings = new SettingsBuilder().enableAlias(true)
                                                    .enableMan(true)
                                                    .ansi(useAnsi)
                                                    .parseOperators(false)
                                                    .inputStream(PausableInput.INSTANCE);

    if(commandLine.hasOption("irc")) {
      settings = settings.inputrc(new File(commandLine.getOptionValue("irc")));
    }

    return new Console(settings.create());
  }

  /**
   * Retrieves the Stellar properties. The properties are either loaded from a file in
   * the classpath or a set of defaults are used.
   */
  private Properties getStellarProperties(CommandLine commandLine) throws IOException {
    Properties properties = new Properties();

    if (commandLine.hasOption("p")) {

      // first attempt to load properties from a file specified on the command-line
      try (InputStream in = new FileInputStream(commandLine.getOptionValue("p"))) {
        if(in != null) {
          properties.load(in);
        }
      }

    } else {

      // otherwise attempt to load properties from the classpath
      try (InputStream in = getClass().getClassLoader().getResourceAsStream(STELLAR_PROPERTIES_FILENAME)) {
        if(in != null) {
          properties.load(in);
        }
      }
    }

    return properties;
  }

  /**
   * Handles the main loop for the REPL.
   */
  public void execute() {

    // welcome message and print globals
    writeLine(WELCOME);
    executor.getContext()
            .getCapability(Context.Capabilities.GLOBAL_CONFIG, false)
            .ifPresent(conf -> writeLine(conf.toString()));

    console.start();
  }

  /**
   * Handles user interaction when executing a Stellar expression.
   * @param expression The expression to execute.
   */
  private void handleStellar(String expression) {

    String stellarExpression = expression;
    String variable = null;
    if(StellarAssignment.isAssignment(expression)) {
      StellarAssignment expr = StellarAssignment.from(expression);
      variable = expr.getVariable();
      stellarExpression = expr.getStatement();
    }
    else {
      if (!stellarExpression.isEmpty()) {
        stellarExpression = stellarExpression.trim();
      }
    }

    try {
      Object result = executor.execute(stellarExpression);
      if (result != null && variable == null) {
        writeLine(result.toString());
      }
      if (variable != null) {
        executor.assign(variable, stellarExpression, result);
      }
    } catch (Throwable t) {
      if(variable != null) {
        writeLine(String.format("%s ERROR: Variable %s not assigned", ERROR_PROMPT, variable));
      }
      writeLine(ERROR_PROMPT + t.getMessage());
      t.printStackTrace();
    }
  }

  /**
   * Handles user interaction when executing a Magic command.
   * @param rawExpression The expression to execute.
   */
  private void handleMagic( String rawExpression) {
    String expression = rawExpression.trim();
    if(MAGIC_FUNCTIONS.equals(expression)) {

      // list all functions
      String functions = StreamSupport
              .stream(executor.getFunctionResolver().getFunctionInfo().spliterator(), false)
              .map(info -> String.format("%s", info.getName()))
              .sorted()
              .collect(Collectors.joining(", "));
      writeLine(functions);

    } else if(MAGIC_VARS.equals(expression)) {

      // list all variables

      executor.getVariables()
              .forEach((k,v) -> writeLine(String.format("%s = %s", k, v)));

    } else {
      writeLine(ERROR_PROMPT + "undefined magic command: " + expression);
    }
  }

  /**
   * Handles user interaction when executing a doc command.
   * @param expression The expression to execute.
   */
  private void handleDoc(String expression) {

    String functionName = StringUtils.substring(expression, 1);
    StreamSupport
            .stream(executor.getFunctionResolver().getFunctionInfo().spliterator(), false)
            .filter(info -> StringUtils.equals(functionName, info.getName()))
            .map(info -> format(info))
            .forEach(doc -> write(doc));
  }

  /**
   * Formats the Stellar function info object into a readable string.
   * @param info The stellar function info object.
   * @return A readable string.
   */
  private String format(StellarFunctionInfo info) {
    StringBuffer ret = new StringBuffer();
    ret.append(info.getName() + "\n");
    ret.append(String.format("Description: %-60s\n\n", info.getDescription()));
    if(info.getParams().length > 0) {
      ret.append("Arguments:\n");
      for(String param : info.getParams()) {
        ret.append(String.format("\t%-60s\n", param));
      }
      ret.append("\n");
    }
    ret.append(String.format("Returns: %-60s\n", info.getReturns()));

    return ret.toString();
  }

  /**
   * Is a given expression a built-in magic?
   * @param expression The expression.
   */
  private boolean isMagic(String expression) {
    return StringUtils.startsWith(expression, MAGIC_PREFIX);
  }

  /**
   * Is a given expression asking for function documentation?
   * @param expression The expression.
   */
  private boolean isDoc(String expression) {
    return StringUtils.startsWith(expression, DOC_PREFIX);
  }

  private void write(String out) {
    System.out.print(out);
  }

  private void writeLine(String out) {
    console.getShell().out().println(out);
  }

  @Override
  public int execute(ConsoleOperation output) throws InterruptedException {
    String expression = output.getBuffer().trim();
    if(StringUtils.isNotBlank(expression) ) {
      if(isMagic(expression)) {
        handleMagic( expression);

      } else if(isDoc(expression)) {
        handleDoc(expression);

      } else if (expression.equals("quit")) {
        try {
          console.stop();
        } catch (Throwable e) {
          e.printStackTrace();
        }
      }
      else if(expression.charAt(0) == '#') {
        return 0;
      }
      else {
        handleStellar(expression);
      }
    }

    return 0;
  }

  @Override
  public void complete(CompleteOperation completeOperation) {
    if(!completeOperation.getBuffer().isEmpty()) {
      String lastToken = Iterables.getLast(Splitter.on(" ").split(completeOperation.getBuffer()), null);
      if(lastToken != null && !lastToken.isEmpty()) {
        lastToken = lastToken.trim();
        final String lastBit = lastToken;
        final boolean isDocRequest = isDoc(lastToken);
        if(isDocRequest) {
          lastToken = lastToken.substring(1);
        }
        StellarExecutor.OperationType opType = StellarExecutor.OperationType.NORMAL;
        if(isDocRequest) {
          opType = StellarExecutor.OperationType.DOC;
        }
        else if(isMagic(lastToken)) {
          opType = StellarExecutor.OperationType.MAGIC;
        }
        Iterable<String> candidates = executor.autoComplete(lastToken, opType);
        if(candidates != null && !Iterables.isEmpty(candidates)) {
          completeOperation.setCompletionCandidates( Lists.newArrayList(
                  Iterables.transform(candidates, s -> stripOff(completeOperation.getBuffer(), lastBit) + s )
                  )
          );
        }
      }
    }

  }

  private static String stripOff(String baseString, String lastBit) {
    int index = baseString.lastIndexOf(lastBit);
    if(index < 0) {
      return baseString;
    }
    return baseString.substring(0, index);
  }
}
