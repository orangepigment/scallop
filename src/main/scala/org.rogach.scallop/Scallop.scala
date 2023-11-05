package org.rogach.scallop

import org.rogach.scallop.exceptions._

import scala.collection.{Seq => CSeq}

private[scallop] object Scallop {

  /** Create the new parser with some arguments already inserted.
    *
    * @param args Args to pre-insert.
    */
  def apply(args: CSeq[String]): Scallop = new Scallop(args)

  /** Create the default empty parser, fresh as mountain air. */
  def apply(): Scallop = apply(Nil)

  private[scallop] def builtinHelpOpt =
    SimpleOption(
      name = "help",
      short = None,
      descr = "Show help message",
      required = false,
      converter = flagConverter,
      default = () => None,
      validator = (_) => true,
      argName = "",
      hidden = false,
      noshort = true
    )

  private[scallop] def builtinVersionOpt =
    SimpleOption(
      name = "version",
      short = None,
      descr = "Show version of this program",
      required = false,
      converter = flagConverter,
      default = () => None,
      validator = (_) => true,
      argName = "",
      hidden = false,
      noshort = true
    )
}

/** Internal configuration builder. */
case class Scallop(
  args: CSeq[String] = Nil,
  opts: List[CliOption] = Nil,
  mainOptions: List[CliOption] = Nil,
  optionGroups: List[(String, Seq[CliOption])] = Nil,
  vers: Option[String] = None,
  bann: Option[String] = None,
  foot: Option[String] = None,
  descr: String = "",
  helpWidth: Option[Int] = None,
  shortSubcommandsHelp: Boolean = false,
  appendDefaultToDescription: Boolean = false,
  noshort: Boolean = false,
  helpFormatter: ScallopHelpFormatter = new ScallopHelpFormatter,
  subbuilders: List[(String, Scallop)] = Nil
) extends ScallopArgListLoader {

  var parent: Option[Scallop] = None

  case class CliOptionInvocation(
    opt: CliOption,
    invocation: String,
    args: List[String],
    error: Option[ScallopException] = None
  )

  type Parsed = List[CliOptionInvocation]

  case class ParseResult(
    opts: Parsed = Nil,
    subcommand: Option[String] = None,
    subcommandArgs: List[String] = Nil
  )

  /** Parse the argument into list of options and their arguments. */
  private def parse(args: CSeq[String]): ParseResult = {
    subbuilders.filter(s => args.contains(s._1)).sortBy(s => args.indexOf(s._1)).headOption match {
      case Some((name, sub)) =>
        ParseResult(
          parse(Nil, args.takeWhile(name != _).toList, Nil),
          Some(name),
          args.dropWhile(name != _).drop(1).toList
        )
      case None =>
        ParseResult(parse(Nil, args.toList, Nil))
    }
  }
  @annotation.tailrec
  private def parse(
    acc: Parsed,
    args: List[String],
    leadingArgsAcc: List[String]
  ): Parsed = {

    def goParseRest(
      leadingArgs: List[String],
      lastMultiArgOption: Option[(CliOption, String)],
      trailingArgs: List[String]
    ): Parsed = {
      def parseRest(): Parsed = {
        val trailingOptions = opts.filter(_.isPositional)

        (lastMultiArgOption, trailingOptions) match {
          case (None, singleTrailingOption :: Nil) if singleTrailingOption.converter.argType == ArgType.LIST =>
            List(CliOptionInvocation(
              opt = singleTrailingOption,
              invocation = "",
              args = leadingArgs ++ trailingArgs
            ))
          case (Some((singleOption, invocation)), Nil) if leadingArgs.isEmpty && singleOption.converter.argType == ArgType.LIST =>
            List(CliOptionInvocation(
              opt = singleOption,
              invocation = invocation,
              args = trailingArgs
            ))
          case _ =>
            val result = TrailingArgumentsParser.parse(
              leadingArgs,
              lastMultiArgOption,
              trailingArgs,
              trailingOptions
            )
            result match {
              case TrailingArgumentsParser.ParseResult(_, _, excess) if excess.nonEmpty =>
                throw ExcessArguments(excess)

              case TrailingArgumentsParser.ParseResult(result, _, _) =>
                result.flatMap {
                  case (option, invocation, Right(args)) =>
                    if (args.nonEmpty || option.required) {
                      List(CliOptionInvocation(
                        opt = option,
                        invocation = invocation,
                        args = args
                      ))
                    } else {
                      Nil
                    }
                  case (option, invocation, Left((message, args))) =>
                    if (option.required && (message == "not enough arguments")) {
                      List(CliOptionInvocation(
                        opt = option,
                        invocation = invocation,
                        args = args,
                        error = Some(RequiredOptionNotFound(option.name))
                      ))
                    } else {
                      List(CliOptionInvocation(
                        opt = option,
                        invocation = invocation,
                        args = args,
                        error = Some(WrongOptionFormat(option.name, args.mkString(" "), message))
                      ))
                    }
                }
            }
        }
      }

      lastMultiArgOption match {
        case Some((option, invocation)) =>
          option.converter.argType match {
            // handle simple option types immediately to avoid going into trailing args parsing with extra options
            case ArgType.FLAG =>
              CliOptionInvocation(option, invocation, Nil) :: goParseRest(leadingArgs, None, trailingArgs)
            case ArgType.SINGLE =>
              if (trailingArgs.size > 0) {
                CliOptionInvocation(option, invocation, trailingArgs.take(1).toList) :: goParseRest(leadingArgs, None, trailingArgs.tail)
              } else {
                List(CliOptionInvocation(
                  opt = option,
                  invocation = invocation,
                  args = trailingArgs,
                  error = Some(WrongOptionFormat(option.name, trailingArgs.mkString, "you should provide exactly one argument"))
                ))
              }
            // short-circuit parsing when there are no trailing args - to get better error messages
            case ArgType.LIST if trailingArgs.isEmpty =>
              List(CliOptionInvocation(
                opt = option,
                invocation = invocation,
                args = Nil
              ))
            case ArgType.LIST => parseRest()
          }
        case None => parseRest()
      }
    }

    if (args.isEmpty) {
      if (leadingArgsAcc.isEmpty) {
        acc.reverse
      } else {
        // only trailing args left - proceed to trailing args parsing
        acc.reverse ::: goParseRest(Nil, None, leadingArgsAcc.reverse ::: removeFirstTrailingArgsSeparator(args))
      }
    } else if (args.head == "--") {
      // separator overrides any options that may follow, all remaining arguments go into trailing arguments
      acc.reverse ::: goParseRest(leadingArgsAcc.reverse, None, args.tail)
    } else if (isOptionName(args.head)) {
      if (args.head.startsWith("--")) {
        opts.find(_.longNames.exists(name => args.head.startsWith("--" + name + "="))) match {

          // parse --arg=value option style
          case Some(opt) =>
            val (invocation, arg) = args.head.drop(2).span('=' != _)
            parse(
              acc = CliOptionInvocation(opt, invocation, List(arg.drop(1))) :: acc,
              args = args.tail,
              leadingArgsAcc = leadingArgsAcc
            )

          // parse --arg value... option style
          case None =>
            val invocation = args.head.drop(2)
            val option =
              opts.find(_.longNames.contains(invocation))
              .orElse(if (invocation == "help") Some(getHelpOption) else None)
              .orElse(if (invocation == "version") getVersionOption else None)
              .getOrElse(NonexistentOption)
            val (matchedArgs, remainingArgs) =
              option.converter.argType match {
                case ArgType.FLAG => (Nil, args.tail)
                case ArgType.SINGLE => (args.tail.take(1), args.tail.drop(1))
                case ArgType.LIST => args.tail.span(isArgument)
              }

            if (option == NonexistentOption) {
              val error = Some(UnknownOption(invocation))
              if (remainingArgs.isEmpty) {
                (CliOptionInvocation(option, invocation, matchedArgs.toList, error) :: acc).reverse
              } else {
                parse(
                  acc = CliOptionInvocation(option, invocation, matchedArgs.toList, error) :: acc,
                  args = remainingArgs,
                  leadingArgsAcc = leadingArgsAcc
                )
              }
            } else if (remainingArgs.isEmpty) {
              // proceed to trailing args parsing
              acc.reverse ::: goParseRest(leadingArgsAcc.reverse, Some((option, invocation)), args.tail)
            } else {
              parse(
                acc = CliOptionInvocation(option, invocation, matchedArgs.toList) :: acc,
                args = remainingArgs,
                leadingArgsAcc = leadingArgsAcc
              )
            }
        }
      } else {
        if (args.head.size == 2) {
          val invocation = args.head.drop(1)
          val option = getOptionWithShortName(args.head(1)).getOrElse(NonexistentOption)
          val (matchedArgs, remainingArgs) =
            option.converter.argType match {
              case ArgType.FLAG => (Nil, args.tail)
              case ArgType.SINGLE => (args.tail.take(1), args.tail.drop(1))
              case ArgType.LIST => args.tail.span(isArgument)
            }

          if (option == NonexistentOption) {
            val error = Some(UnknownOption(invocation))
            if (remainingArgs.isEmpty) {
              (CliOptionInvocation(option, invocation, matchedArgs.toList, error) :: acc).reverse
            } else {
              parse(
                acc = CliOptionInvocation(option, invocation, matchedArgs.toList, error) :: acc,
                args = remainingArgs,
                leadingArgsAcc = leadingArgsAcc
              )
            }
          } else if (remainingArgs.isEmpty) {
            // proceed to trailing args parsing
            acc.reverse ::: goParseRest(leadingArgsAcc.reverse, Some((option, invocation)), args.tail)
          } else {
            parse(
              acc = CliOptionInvocation(option, invocation, matchedArgs.toList) :: acc,
              args = remainingArgs,
              leadingArgsAcc = leadingArgsAcc
            )
          }
        } else {
          val option = getOptionWithShortName(args.head(1)).getOrElse(NonexistentOption)
          if (option.converter.argType != ArgType.FLAG) {
            parse(
              acc = acc,
              args = args.head.take(2) :: args.head.drop(2) :: args.tail,
              leadingArgsAcc = leadingArgsAcc
            )
          } else {
            parse(
              acc = acc,
              args = args.head.take(2) :: ("-" + args.head.drop(2)) :: args.tail,
              leadingArgsAcc = leadingArgsAcc
            )
          }
        }
      }
    } else if (args.head.matches("-[0-9]+")) {
      // parse number-only options
      val alreadyMatchedNumbers = acc.count(_.opt.isInstanceOf[NumberArgOption])
      opts.filter(_.isInstanceOf[NumberArgOption]).drop(alreadyMatchedNumbers).headOption match {
        case Some(opt) =>
          val num = args.head.drop(1)
          parse(
            acc = CliOptionInvocation(opt, num, List(num)) :: acc,
            args = args.tail,
            leadingArgsAcc = leadingArgsAcc
          )
        case None =>
          parse(
            acc,
            args = args.tail,
            leadingArgsAcc = args.head :: leadingArgsAcc
          )
      }
    } else {
      // args.head is not an option, so it is a "leading trailing argument":
      // trailing argument that may be followed by some options
      parse(
        acc,
        args = args.tail,
        leadingArgsAcc = args.head :: leadingArgsAcc
      )
    }
  }

  /** Find an option, that responds to this short name. */
  def getOptionWithShortName(c: Char): Option[CliOption] = {
    opts
    .find(_.requiredShortNames.contains(c))
    .orElse {
      opts.find(_.shortNames.contains(c))
    }
    .orElse(Option(getHelpOption).find(_.requiredShortNames.contains(c)))
    .orElse(getVersionOption.find(_.requiredShortNames.contains(c)))
  }

  def getOptionShortNames(opt: CliOption): List[Char] = {
    (opt.shortNames ++ opt.requiredShortNames).distinct
    .filter(sh => getOptionWithShortName(sh).get == opt)
  }

  /** Result of parsing */
  private lazy val parsed: ParseResult = parse(loadArgList(args))

  /** Tests whether this string contains option name, not some number. */
  private def isOptionName(s: String) =
    if (s.startsWith("-"))
      if (s.size > 1)
        !s(1).isDigit
      else if (s.size == 1)
        false
      else true
    else false

  /** Tests whether this string contains option parameter, not option call. */
  private def isArgument(s: String) = !isOptionName(s)

  private def removeFirstTrailingArgsSeparator(args: List[String]): List[String] = {
    val (argsBeforeSeparator, argsAfterSeparator) = args.span("--" != _)
    argsBeforeSeparator ::: argsAfterSeparator.drop(1)
  }

  def appendOption(option: CliOption): Scallop = {
    this.copy(opts = opts :+ option)
  }

  /** Adds a subbuilder (subcommand) to this builder.
    * @param name All arguments after this string would be routed to this builder.
    */
  def addSubBuilder(nameAndAliases: Seq[String], builder: Scallop) = {
    builder.parent = Some(this)
    this.copy(subbuilders = subbuilders ++ nameAndAliases.map(name => name -> builder))
  }

  /** Traverses the tree of subbuilders, using the provided name.
    * @param name Names of subcommand names, that lead to the needed builder, separated by \\0.
    */
  def findSubbuilder(name: String): Option[Scallop] = {
    if (name.contains('\u0000')) {
      val (firstSub, rest) = name.span('\u0000' != _)
      subbuilders.find(_._1 == firstSub).flatMap(_._2.findSubbuilder(rest.tail))
    } else subbuilders.find(_._1 == name).map(_._2)
  }

  /** Retrieves name of the subcommand that was found in input arguments. */
  def getSubcommandName = parsed.subcommand

  /** Retrieves the subbuilder object,
    * that matches the name of the subcommand found in input arguments. */
  def getSubbuilder: Option[Scallop] = parsed.subcommand.flatMap { sn =>
    subbuilders.find(_._1 == sn).map(_._2)
  }

  /** Returns the subcommand arguments. */
  def getSubcommandArgs: List[String] = parsed.subcommandArgs

  /** Returns the list of subcommand names, recursively. */
  def getSubcommandNames: List[String] = {
    parsed.subcommand.map(subName => subbuilders.find(_._1 == subName).map(s => s._1 :: s._2.args(parsed.subcommandArgs).getSubcommandNames).getOrElse(Nil)).getOrElse(Nil)
  }

  /** Retrieves a list of all supplied options (including options from subbuilders). */
  def getAllSuppliedOptionNames: List[String] = {
    opts.map(_.name).filter(isSupplied) ::: parsed.subcommand.map(subName => subbuilders.find(_._1 == subName).map(s => s._2.args(parsed.subcommandArgs)).get.getAllSuppliedOptionNames.map(subName + "\u0000" + _)).getOrElse(Nil)
  }

  /** Add version string to this builder.
    *
    * @param v Version string, to be printed before all other things in help.
    */
  def version(v: String) = this.copy(vers = Some(v))

  /** Add banner string to this builder. Banner should describe your program and provide a short
    * summary on it's usage.
    *
    * @param b Banner string, can contain multiple lines. Note this is not formatted to 80 characters!
    */
  def banner(b: String) = this.copy(bann = Some(b))

  /** Add footer string to this builder. Footer will be printed in help after option definitions.
    *
    * @param f Footer string, can contain multiple lines. Note this is not formatted to 80 characters!
    */
  def footer(f: String) = this.copy(foot = Some(f))

  /** Explicitly sets the needed width for the help printout. */
  def setHelpWidth(w: Int) = this.copy(helpWidth = Some(w))

  /** Get help on options from this builder. The resulting help is carefully formatted to required number of columns (default = 80, change with .setHelpWidth method),
    * and contains info on properties, options and trailing arguments.
    */
  def help: String = helpFormatter.formatHelp(this, "")

  /** Get full help text (with version, banner, option usage and footer) */
  def getFullHelpString(): String = {
    Seq(vers, bann, Some(help), foot).flatten.mkString("\n")
  }

  /** Print help message (with version, banner, option usage and footer) to stdout. */
  def printHelp() = {
    println(getFullHelpString())
  }

  /** Add some more arguments to this builder. They are appended to the end of the original list.
    *
    * @param a arg list to add
    */
  def args(a: Seq[String]): Scallop = this.copy(args = args ++ a)

  /** Tests if this option or trailing arg was explicitly provided by argument list (not from default).
    *
    * @param name Identifier of option or trailing arg definition
    */
  def isSupplied(name: String): Boolean = {
    if (name.contains('\u0000')) {
      // delegating to subbuilder
      parsed.subcommand.map { subc =>
        subbuilders
        .find(_._1 == subc).map(_._2)
        .filter { subBuilder =>
          subbuilders.filter(_._2 == subBuilder)
          .exists(_._1 == name.takeWhile('\u0000' != _))
        }
        .map { subBuilder =>
          subBuilder.args(parsed.subcommandArgs).isSupplied(name.dropWhile('\u0000' != _).drop(1))
        }.getOrElse(false) // only current subcommand can have supplied arguments
      }.getOrElse(false) // no subcommands, so their options are definitely not supplied
    } else {
      opts find (_.name == name) map { opt =>
        val args = parsed.opts.filter(_.opt == opt).map(i => (i.invocation, i.args))
        opt.converter.parseCached(args) match {
          case Right(Some(_)) => true
          case _ => false
        }
      } getOrElse(throw new UnknownOption(name))
    }
  }

   /** Get the value of option (or trailing arg) as Option.
     * @param name Name for option.
     */
  def get(name: String): Option[Any] = {
    if (name.contains('\u0000')) {
      // delegating to subbuilder
      subbuilders.find(_._1 == name.takeWhile('\u0000' != _)).map(_._2.args(parsed.subcommandArgs).get(name.dropWhile('\u0000' != _).drop(1)))
        .getOrElse(throw new UnknownOption(name.replace("\u0000",".")))
    } else {
      opts.find(_.name == name).map { opt =>
        val args = parsed.opts.filter(_.opt == opt).map(i => (i.invocation, i.args))
        opt.converter.parseCached(args) match {
          case Right(parseResult) =>
            parseResult.orElse(opt.default())
          case _ => if (opt.required) throw new MajorInternalException else None
        }
      }.getOrElse(throw new UnknownOption(name))
    }
  }

  def get(name: Char): Option[Any] = get(name.toString)

  /** Get the value of option. If option is not found, this will throw an exception.
    *
    * @param name Name for option.
    */
  def apply(name: String): Any = get(name).get

  def apply(name: Char): Any = apply(name.toString)

  def prop(name: Char, key: String): Option[Any] = apply(name).asInstanceOf[Map[String, Any]].get(key)

  lazy val getHelpOption =
    opts.find(_.name == "help")
    .getOrElse(
      if (opts.exists(opt => getOptionShortNames(opt).contains('h'))) {
        Scallop.builtinHelpOpt
      } else {
        Scallop.builtinHelpOpt.copy(short = Some('h'), noshort = false)
      }
    )

  lazy val getVersionOption =
    vers.map(_ => opts.find(_.name == "version")
    .getOrElse(
      if (opts.exists(opt => getOptionShortNames(opt).contains('v'))) {
        Scallop.builtinVersionOpt
      } else {
        Scallop.builtinVersionOpt.copy(short = Some('v'), noshort = false)
      }
    ))

  /** Verify the builder. Parses arguments, makes sure no definitions clash, no garbage or unknown options are present,
    * and all present arguments are in proper format. It is recommended to call this method before using the results.
    *
    * If there is "--help" or "--version" option present, it prints help or version statement and exits.
    */
  def verify: Scallop = {
    // option identifiers must not clash
    opts map (_.name) groupBy (a=>a) filter (_._2.size > 1) foreach
      (a => throw new IdenticalOptionNames(Util.format("Option identifier '%s' is not unique", a._1)))
    // long options names must not clash
    opts flatMap (_.longNames) groupBy (a=>a) filter (_._2.size > 1) foreach
      (a => throw new IdenticalOptionNames(Util.format("Long option name '%s' is not unique", a._1)))
    // short options names must not clash
    opts flatMap (o => (o.requiredShortNames).distinct) groupBy (a=>a) filter (_._2.size > 1) foreach
      (a => throw new IdenticalOptionNames(Util.format("Short option name '%s' is not unique", a._1)))

    // trigger actual parsing
    parsed

    if (parsed.opts.exists(_.opt == getHelpOption)) {
      throw Help("")
    }

    getVersionOption.foreach { versionOpt =>
      if (parsed.opts.headOption.exists(_.opt == versionOpt)) {
        throw Version
      }
    }

    parsed.opts.foreach { invocation =>
      invocation.error.foreach { exception =>
        throw exception
      }
    }

    // verify subcommand parsing
    parsed.subcommand.map { sn =>
      subbuilders.find(_._1 == sn).map { case (sn, sub)=>
        try {
          sub.args(parsed.subcommandArgs).verify
        } catch {
          case Help("") => throw Help(sn)
          case h @ Help(subname) => throw Help(sn + "\u0000" + subname)
        }
      }
    }

    opts foreach { o =>
      val args = parsed.opts.filter(_.opt == o).map(i => (i.invocation, i.args))
      val res = o.converter.parseCached(args)
      res match {
        case Left(msg) =>
          throw new WrongOptionFormat(o.name, args.map(_._2.mkString(" ")).mkString(" "), msg)
        case _ =>
      }
      if (o.required && !res.fold(_ => false, _.isDefined) && !o.default().isDefined)
        throw new RequiredOptionNotFound(o.name)
      // validaiton
      if (!(get(o.name) map (v => o.validator(v)) getOrElse true))
        throw new ValidationFailure(Util.format("Validation failure for '%s' option parameters: %s", o.name, args.map(_._2.mkString(" ")).mkString(" ")))

    }

    this
  }

  /** Get summary of current parser state.
    *
    * Returns a list of all options in the builder, and corresponding values for them.
    */
  def summary: String = {
    Util.format("Scallop(%s)", args.mkString(", ")) + "\n" + filteredSummary(Set.empty)
  }

  /** Get summary of current parser state, hididng values for some of the options.
    * Useful if you log the summary and want to avoid storing sensitive information
    * in the logs (like passwords)
    *
    * @param blurred names of the options that should be hidden.
    * @return a list of all options in the builder
    */
  def filteredSummary(blurred: Set[String]): String = {
    lazy val hide = "************"
    opts.map { o =>
      Util.format(
        " %s  %s => %s",
        (if (isSupplied(o.name)) "*" else " "),
        o.name,
        if(!blurred.contains(o.name)) get(o.name).getOrElse("<None>") else hide
      )
    }.mkString("\n") + "\n" + parsed.subcommand.map { sn =>
      Util.format("subcommand: %s\n", sn) + subbuilders.find(_._1 == sn).get._2.args(parsed.subcommandArgs).filteredSummary(blurred)
    }.getOrElse("")
  }

}
