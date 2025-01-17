package fix

import metaconfig.Configured
import scalafix.v1._

import scala.annotation.tailrec
import scala.meta._

final class UseNamedParameters(config: UseNamedParametersConfig)
    extends SemanticRule(classOf[UseNamedParameters].getSimpleName) {
  def this() = this(UseNamedParametersConfig.default)

  override def withConfiguration(config: Configuration): Configured[Rule] = {
    val requiredScalacOption = "-P:semanticdb:synthetics:on"
    if (config.scalacOptions.contains(requiredScalacOption)) {
      config.conf
        .getOrElse(this.getClass.getSimpleName)(this.config)
        .map(newConfig => new UseNamedParameters(newConfig))
    } else {
      Configured.error(
        s"""This rule requires SemanticDB synthetics to work properly (e.g. to detect case class apply).
          |Please add "$requiredScalacOption" to scala compiler options (e.g. scalacOptions in SBT).""".stripMargin
      )
    }
  }

  override def fix(implicit doc: SemanticDocument): Patch = {
    doc.tree
      .collect {
        case Init(_, name, argss) if !hasPlaceholder(argss.flatten) =>
          resolveScalaMethodSignatureFromSymbol(name.symbol) match {
            case Some(methodSig) =>
              val patchGens: List[(Term, Int) => Patch] =
                methodSig.parameterLists.zipWithIndex.map { case (_, idx) => mkPatchGenForArgList(config, methodSig, idx) }
              argss
                .zip(patchGens)
                .flatMap { case (argsInBlock, patchGen) =>
                    argsInBlock.zipWithIndex.map { case (t, idx) => patchGen(t, idx) }
                }
            case None => List.empty
          }
        case Term.Apply(fun, args) if !hasPlaceholder(args) => {
            val fname = resolveFunctionTerm(fun)
            val methodSignatureOpt =
              resolveScalaMethodSignatureFromSymbol(fname.symbol).orElse(resolveFromSynthetics(fname))
            methodSignatureOpt match {
              case Some(methodSig)
                  if methodSig.parameterLists.nonEmpty => // parameterLists.nonEmpty filters out FunctionX types
                val patchGen: (Term, Int) => Patch =
                  mkPatchGenForArgList(config, methodSig, determineParamBlockIndex(fname))
                args.zipWithIndex.map { case (t, idx) => patchGen(t, idx) }
              case _ => List.empty
            }
        }
      }
      .flatten
      .asPatch
  }

  private def hasPlaceholder(argTerms: List[Term]): Boolean =
    argTerms.collect{ case Term.Placeholder() => true }.exists(x => x)

  private def resolveFunctionTerm(term: Term): Term =
    term match {
      case fname: Term.Name => fname
      case fname: Term.Apply =>
        // For curried functions, return the Term as is as we need
        // it to figure out which param block we're currently handling
        fname
      case Term.ApplyType(fname, _) => fname
      case s: Term.Select => s.name
    }

  private def mkPatchGenForArgList(
    config: UseNamedParametersConfig,
    methodSig: MethodSignature,
    paramBlockIdx: Int
  )(implicit doc: SemanticDocument): (Term, Int) => Patch = {
    val thisParamBlock = methodSig.parameterLists(paramBlockIdx)
        // Whether to apply named param patching is dependent on parameters
        // in the method definition, not use site.
        if (thisParamBlock.length < config.minParams) {
          (_, _) => Patch.empty
        }else {
          (term: Term, idx: Int) => {
            term match {
              case _: Term.Assign => Patch.empty // Already using named param, no patch needed
              case t =>
                // Term.Name will escape any weird identifiers
                thisParamBlock.lift(idx) match {
                  case Some(symInfo) =>
                    val paramName = Term.Name(symInfo.displayName).toString
                    Patch.addLeft(t, s"$paramName = ")
                  case None => // Var args
                    Patch.empty
                }
            }
          }
        }
  }

  private def resolveScalaMethodSignatureFromSymbol(
    funcSymbol: Symbol
  )(implicit doc: SemanticDocument): Option[MethodSignature] =
    funcSymbol.info.flatMap { symInfo =>
      if (symInfo.isScala)
        symInfo.signature match {
          case m: MethodSignature => Some(m)
          case _ => None
        }
      else None
    }

  // To resolve companion object .apply methods
  private def resolveFromSynthetics(funcTerm: Term)(implicit doc: SemanticDocument): Option[MethodSignature] = {
    funcTerm.synthetics
      .flatMap(_.symbol)
      .flatMap(_.info)
      .map(_.signature)
      .collectFirst { case m: MethodSignature =>
        m
      }
  }

  @tailrec
  private def determineParamBlockIndex(curFuncTerm: Term, curIndex: Int = 0): Int =
    curFuncTerm match {
      case Term.Apply(innerFuncTerm, _) => determineParamBlockIndex(innerFuncTerm, curIndex + 1)
      case _ => curIndex
    }
}
