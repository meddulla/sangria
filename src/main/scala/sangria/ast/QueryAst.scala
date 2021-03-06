package sangria.ast

import org.parboiled2.Position
import sangria.parser.SourceMapper
import sangria.renderer.QueryRenderer
import sangria.validation.DocumentAnalyzer

import sangria.schema.Schema
import sangria.validation.{TypeInfo, Violation}
import sangria.visitor._

import scala.util.control.Breaks._

import scala.collection.immutable.ListMap

case class Document(definitions: Vector[Definition], trailingComments: Vector[Comment] = Vector.empty, position: Option[Position] = None, sourceMapper: Option[SourceMapper] = None) extends AstNode with WithTrailingComments {
  lazy val operations = Map(definitions collect {case op: OperationDefinition ⇒ op.name → op}: _*)
  lazy val fragments = Map(definitions collect {case fragment: FragmentDefinition ⇒ fragment.name → fragment}: _*)
  lazy val source = sourceMapper map (_.source)

  def operationType(operationName: Option[String] = None): Option[OperationType] =
    operation(operationName) map (_.operationType)

  def operation(operationName: Option[String] = None): Option[OperationDefinition] =
    if (operations.size != 1 && operationName.isEmpty)
      None
    else
      operationName flatMap (opName ⇒ operations get Some(opName)) orElse operations.values.headOption

  def withoutSourceMapper = copy(sourceMapper = None)

  override def canEqual(other: Any): Boolean = other.isInstanceOf[Document]

  override def equals(other: Any): Boolean = other match {
    case that: Document ⇒
      (that canEqual this) &&
        definitions == that.definitions &&
        position == that.position
    case _ ⇒ false
  }

  /**
    * Merges two documents. The `sourceMapper` is lost along the way.
    */
  def merge(other: Document) = Document.merge(Vector(this, other))

  lazy val analyzer = DocumentAnalyzer(this)

  lazy val separateOperations: Map[Option[String], Document] = analyzer.separateOperations

  def separateOperation(definition: OperationDefinition) = analyzer.separateOperation(definition)
  def separateOperation(operationName: Option[String]) = analyzer.separateOperation(operationName)

  override def hashCode(): Int =
    Seq(definitions, position).map(_.hashCode()).foldLeft(0)((a, b) ⇒ 31 * a + b)
}

object Document {
  /**
    * Provided a collection of ASTs, presumably each from different files,
    * concatenate the ASTs together into batched AST, useful for validating many
    * GraphQL source files which together represent one conceptual application.
    *
    * The result of the merge will loose the `sourceMapper` and `position` since
    * connection to the original string source is lost.
    */
  def merge(documents: Traversable[Document]): Document =
    Document(documents.toVector.flatMap(_.definitions))
}

sealed trait ConditionalFragment extends AstNode {
  def typeConditionOpt: Option[NamedType]
}

sealed trait WithComments extends AstNode {
  def comments: Vector[Comment]
}

sealed trait WithTrailingComments {
  def trailingComments: Vector[Comment]
}

sealed trait SelectionContainer extends AstNode with WithComments with WithTrailingComments {
  def selections: Vector[Selection]
  def position: Option[Position]
}

sealed trait Definition extends AstNode

case class OperationDefinition(
  operationType: OperationType = OperationType.Query,
  name: Option[String] = None,
  variables: Vector[VariableDefinition] = Vector.empty,
  directives: Vector[Directive] = Vector.empty,
  selections: Vector[Selection],
  comments: Vector[Comment] = Vector.empty,
  trailingComments: Vector[Comment] = Vector.empty,
  position: Option[Position] = None) extends Definition with WithDirectives with SelectionContainer

case class FragmentDefinition(
    name: String,
    typeCondition: NamedType,
    directives: Vector[Directive],
    selections: Vector[Selection],
    comments: Vector[Comment] = Vector.empty,
    trailingComments: Vector[Comment] = Vector.empty,
    position: Option[Position] = None) extends Definition with ConditionalFragment with WithDirectives with SelectionContainer {
  lazy val typeConditionOpt = Some(typeCondition)
}

sealed trait OperationType

object OperationType {
  case object Query extends OperationType
  case object Mutation extends OperationType
  case object Subscription extends OperationType
}

case class VariableDefinition(
  name: String,
  tpe: Type,
  defaultValue: Option[Value],
  comments: Vector[Comment] = Vector.empty,
  position: Option[Position] = None) extends AstNode with WithComments

sealed trait Type extends AstNode {
  def namedType: NamedType = {
    @annotation.tailrec
    def loop(tpe: Type): NamedType = tpe match {
      case NotNullType(ofType, _) ⇒ loop(ofType)
      case ListType(ofType, _) ⇒ loop(ofType)
      case named: NamedType ⇒ named
    }

    loop(this)
  }
}

case class NamedType(name: String, position: Option[Position] = None) extends Type
case class NotNullType(ofType: Type, position: Option[Position] = None) extends Type
case class ListType(ofType: Type, position: Option[Position] = None) extends Type

sealed trait Selection extends AstNode with WithDirectives with WithComments

case class Field(
    alias: Option[String],
    name: String,
    arguments: Vector[Argument],
    directives: Vector[Directive],
    selections: Vector[Selection],
    comments: Vector[Comment] = Vector.empty,
    trailingComments: Vector[Comment] = Vector.empty,
    position: Option[Position] = None) extends Selection with SelectionContainer {
  lazy val outputName = alias getOrElse name
}

case class FragmentSpread(
  name: String,
  directives: Vector[Directive],
  comments: Vector[Comment] = Vector.empty,
  position: Option[Position] = None) extends Selection

case class InlineFragment(
    typeCondition: Option[NamedType],
    directives: Vector[Directive],
    selections: Vector[Selection],
    comments: Vector[Comment] = Vector.empty,
    trailingComments: Vector[Comment] = Vector.empty,
    position: Option[Position] = None) extends Selection with ConditionalFragment with SelectionContainer {
  def typeConditionOpt = typeCondition
}

sealed trait NameValue extends AstNode with WithComments {
  def name: String
  def value: Value
}

sealed trait WithDirectives extends AstNode {
  def directives: Vector[Directive]
}

case class Directive(name: String, arguments: Vector[Argument], comments: Vector[Comment] = Vector.empty, position: Option[Position] = None) extends AstNode
case class Argument(name: String, value: Value, comments: Vector[Comment] = Vector.empty, position: Option[Position] = None) extends NameValue

sealed trait Value extends AstNode with WithComments {
  override def renderPretty: String = QueryRenderer.render(this, QueryRenderer.PrettyInput)
}

sealed trait ScalarValue extends Value

case class IntValue(value: Int, comments: Vector[Comment] = Vector.empty, position: Option[Position] = None) extends ScalarValue
case class BigIntValue(value: BigInt, comments: Vector[Comment] = Vector.empty, position: Option[Position] = None) extends ScalarValue
case class FloatValue(value: Double, comments: Vector[Comment] = Vector.empty, position: Option[Position] = None) extends ScalarValue
case class BigDecimalValue(value: BigDecimal, comments: Vector[Comment] = Vector.empty, position: Option[Position] = None) extends ScalarValue
case class StringValue(value: String, comments: Vector[Comment] = Vector.empty, position: Option[Position] = None) extends ScalarValue
case class BooleanValue(value: Boolean, comments: Vector[Comment] = Vector.empty, position: Option[Position] = None) extends ScalarValue
case class EnumValue(value: String, comments: Vector[Comment] = Vector.empty, position: Option[Position] = None) extends Value
case class ListValue(values: Vector[Value], comments: Vector[Comment] = Vector.empty, position: Option[Position] = None) extends Value
case class VariableValue(name: String, comments: Vector[Comment] = Vector.empty, position: Option[Position] = None) extends Value
case class NullValue(comments: Vector[Comment] = Vector.empty, position: Option[Position] = None) extends Value
case class ObjectValue(fields: Vector[ObjectField], comments: Vector[Comment] = Vector.empty, position: Option[Position] = None) extends Value {
  lazy val fieldsByName =
    fields.foldLeft(ListMap.empty[String, Value]) {
      case (acc, field) ⇒ acc + (field.name → field.value)
    }
}

case class ObjectField(name: String, value: Value, comments: Vector[Comment] = Vector.empty, position: Option[Position] = None) extends NameValue

case class Comment(text: String, position: Option[Position] = None) extends AstNode

// Schema Definition

case class ScalarTypeDefinition(
  name: String,
  directives: Vector[Directive] = Vector.empty,
  comments: Vector[Comment] = Vector.empty,
  position: Option[Position] = None) extends TypeDefinition

case class FieldDefinition(
  name: String,
  fieldType: Type,
  arguments: Vector[InputValueDefinition],
  directives: Vector[Directive] = Vector.empty,
  comments: Vector[Comment] = Vector.empty,
  position: Option[Position] = None) extends SchemaAstNode with WithDirectives

case class InputValueDefinition(
  name: String,
  valueType: Type,
  defaultValue: Option[Value],
  directives: Vector[Directive] = Vector.empty,
  comments: Vector[Comment] = Vector.empty,
  position: Option[Position] = None) extends SchemaAstNode with WithDirectives

case class ObjectTypeDefinition(
  name: String,
  interfaces: Vector[NamedType],
  fields: Vector[FieldDefinition],
  directives: Vector[Directive] = Vector.empty,
  comments: Vector[Comment] = Vector.empty,
  trailingComments: Vector[Comment] = Vector.empty,
  position: Option[Position] = None) extends TypeDefinition with WithTrailingComments

case class InterfaceTypeDefinition(
  name: String,
  fields: Vector[FieldDefinition],
  directives: Vector[Directive] = Vector.empty,
  comments: Vector[Comment] = Vector.empty,
  trailingComments: Vector[Comment] = Vector.empty,
  position: Option[Position] = None) extends TypeDefinition with WithTrailingComments

case class UnionTypeDefinition(
  name: String,
  types: Vector[NamedType],
  directives: Vector[Directive] = Vector.empty,
  comments: Vector[Comment] = Vector.empty,
  position: Option[Position] = None) extends TypeDefinition

case class EnumTypeDefinition(
  name: String,
  values: Vector[EnumValueDefinition],
  directives: Vector[Directive] = Vector.empty,
  comments: Vector[Comment] = Vector.empty,
  trailingComments: Vector[Comment] = Vector.empty,
  position: Option[Position] = None) extends TypeDefinition with WithTrailingComments

case class EnumValueDefinition(
  name: String,
  directives: Vector[Directive] = Vector.empty,
  comments: Vector[Comment] = Vector.empty,
  position: Option[Position] = None) extends SchemaAstNode with WithDirectives

case class InputObjectTypeDefinition(
  name: String,
  fields: Vector[InputValueDefinition],
  directives: Vector[Directive] = Vector.empty,
  comments: Vector[Comment] = Vector.empty,
  trailingComments: Vector[Comment] = Vector.empty,
  position: Option[Position] = None) extends TypeDefinition with WithTrailingComments

case class TypeExtensionDefinition(
  definition: ObjectTypeDefinition,
  comments: Vector[Comment] = Vector.empty,
  position: Option[Position] = None) extends TypeSystemDefinition

case class DirectiveDefinition(
  name: String,
  arguments: Vector[InputValueDefinition],
  locations: Vector[DirectiveLocation],
  comments: Vector[Comment] = Vector.empty,
  position: Option[Position] = None) extends TypeSystemDefinition

case class DirectiveLocation(
  name: String,
  comments: Vector[Comment] = Vector.empty,
  position: Option[Position] = None) extends SchemaAstNode

case class SchemaDefinition(
  operationTypes: Vector[OperationTypeDefinition],
  directives: Vector[Directive] = Vector.empty,
  comments: Vector[Comment] = Vector.empty,
  trailingComments: Vector[Comment] = Vector.empty,
  position: Option[Position] = None) extends TypeSystemDefinition with WithTrailingComments with WithDirectives

case class OperationTypeDefinition(
  operation: OperationType,
  tpe: NamedType,
  comments: Vector[Comment] = Vector.empty,
  position: Option[Position] = None) extends SchemaAstNode

sealed trait AstNode {
  def position: Option[Position]
  def cacheKeyHash: Int = System.identityHashCode(this)

  def renderPretty: String = QueryRenderer.render(this, QueryRenderer.Pretty)
  def renderCompact: String = QueryRenderer.render(this, QueryRenderer.Compact)
}

sealed trait SchemaAstNode extends AstNode with WithComments
sealed trait TypeSystemDefinition extends SchemaAstNode with Definition
sealed trait TypeDefinition extends TypeSystemDefinition with WithDirectives {
  def name: String
}

object AstNode {
  def withoutPosition[T <: AstNode](node: T, stripComments: Boolean = false): T = {
    val enterComment = (_: Comment) ⇒ if (stripComments) VisitorCommand.Delete else VisitorCommand.Skip

    visit[AstNode](node,
      Visit[Comment](enterComment),
      VisitAnyField[AstNode, Option[Position]]((_, _) ⇒ VisitorCommand.Transform(None))).asInstanceOf[T]
  }
}

trait AstVisitor {
  def onEnter: PartialFunction[AstNode, VisitorCommand] = {case _ ⇒ VisitorCommand.Continue}
  def onLeave: PartialFunction[AstNode, VisitorCommand] = {case _ ⇒ VisitorCommand.Continue}
}

case class DefaultAstVisitor(
  override val onEnter: PartialFunction[AstNode, VisitorCommand] = {case _ ⇒ VisitorCommand.Continue},
  override val onLeave: PartialFunction[AstNode, VisitorCommand] = {case _ ⇒ VisitorCommand.Continue}
) extends AstVisitor

object AstVisitor {
  import AstVisitorCommand._

  def apply(
    onEnter: PartialFunction[AstNode, VisitorCommand] = {case _ ⇒ VisitorCommand.Continue},
    onLeave: PartialFunction[AstNode, VisitorCommand] = {case _ ⇒ VisitorCommand.Continue}
  ) = DefaultAstVisitor(onEnter, onLeave)

  def simple(
    onEnter: PartialFunction[AstNode, Unit] = {case _ ⇒ ()},
    onLeave: PartialFunction[AstNode, Unit] = {case _ ⇒ ()}
  ) = DefaultAstVisitor(
    {
      case node if onEnter.isDefinedAt(node) ⇒
        onEnter(node)
        VisitorCommand.Continue
    }, {
      case node if onLeave.isDefinedAt(node) ⇒
        onLeave(node)
        VisitorCommand.Continue
    })

  def visit[T <: AstNode](root: T, visitor: AstVisitor): T =
    visit(root,
      node ⇒ if (visitor.onEnter.isDefinedAt(node)) visitor.onEnter(node) else VisitorCommand.Continue,
      node ⇒ if (visitor.onLeave.isDefinedAt(node)) visitor.onLeave(node) else VisitorCommand.Continue)

  def visitAstWithTypeInfo[T <: AstNode](schema: Schema[_, _], root: T)(visitorFn: TypeInfo ⇒ AstVisitor): T = {
    val typeInfo = new TypeInfo(schema)
    val visitor = visitorFn(typeInfo)

    visit(root,
      node ⇒ {
        typeInfo.enter(node)
        if (visitor.onEnter.isDefinedAt(node)) visitor.onEnter(node) else VisitorCommand.Continue
      },
      node ⇒ {
        typeInfo.leave(node)
        if (visitor.onLeave.isDefinedAt(node)) visitor.onLeave(node) else VisitorCommand.Continue
      })
  }

  def visitAstWithState[S](schema: Schema[_, _], root: AstNode, state: S)(visitorFn: (TypeInfo, S) ⇒ AstVisitor): S = {
    val typeInfo = new TypeInfo(schema)
    val visitor = visitorFn(typeInfo, state)

    visit(root,
      node ⇒ {
        typeInfo.enter(node)
        if (visitor.onEnter.isDefinedAt(node)) visitor.onEnter(node) else VisitorCommand.Continue
      },
      node ⇒ {
        typeInfo.leave(node)
        if (visitor.onLeave.isDefinedAt(node)) visitor.onLeave(node) else VisitorCommand.Continue
      })

    state
  }

  def visit[T <: AstNode](
      root: T,
      onEnter: AstNode ⇒ VisitorCommand,
      onLeave: AstNode ⇒ VisitorCommand): T =
    sangria.visitor.visit[AstNode](root, Visit[AstNode](onEnter, onLeave)).asInstanceOf[T]

  @deprecated("Please use `visit` instead. Its implementation is not recursive (so it will not overflow the stack) and allows AST modification.", "1.1.0")
  def visitAst(
      doc: AstNode,
      onEnter: AstNode ⇒ AstVisitorCommand.Value = _ ⇒ Continue,
      onLeave: AstNode ⇒ AstVisitorCommand.Value = _ ⇒ Continue): Unit =
    visitAstRecursive(doc, onEnter, onLeave)

  private[sangria] def visitAstRecursive(
      doc: AstNode,
      onEnter: AstNode ⇒ AstVisitorCommand.Value = _ ⇒ Continue,
      onLeave: AstNode ⇒ AstVisitorCommand.Value = _ ⇒ Continue): Unit = {

    def breakOrSkip(cmd: AstVisitorCommand.Value) = cmd match {
      case Break ⇒ break()
      case Skip ⇒ false
      case Continue ⇒ true
    }

    def loop(node: AstNode): Unit =
      node match {
        case n @ Document(defs, trailingComments, _, _) ⇒
          if (breakOrSkip(onEnter(n))) {
            defs.foreach(d ⇒ loop(d))
            trailingComments.foreach(s ⇒ loop(s))
            breakOrSkip(onLeave(n))
          }
        case n @ FragmentDefinition(_, cond, dirs, sels, comment, trailingComments, _) ⇒
          if (breakOrSkip(onEnter(n))) {
            loop(cond)
            dirs.foreach(d ⇒ loop(d))
            sels.foreach(s ⇒ loop(s))
            comment.foreach(s ⇒ loop(s))
            trailingComments.foreach(s ⇒ loop(s))
            breakOrSkip(onLeave(n))
          }
        case n @ OperationDefinition(_, _, vars, dirs, sels, comment, trailingComments, _) ⇒
          if (breakOrSkip(onEnter(n))) {
            vars.foreach(d ⇒ loop(d))
            dirs.foreach(d ⇒ loop(d))
            sels.foreach(s ⇒ loop(s))
            comment.foreach(s ⇒ loop(s))
            trailingComments.foreach(s ⇒ loop(s))
            breakOrSkip(onLeave(n))
          }
        case n @ VariableDefinition(_, tpe, default, comment, _) ⇒
          if (breakOrSkip(onEnter(n))) {
            loop(tpe)
            default.foreach(d ⇒ loop(d))
            comment.foreach(s ⇒ loop(s))
            breakOrSkip(onLeave(n))
          }
        case n @ InlineFragment(cond, dirs, sels, comment, trailingComments, _) ⇒
          if (breakOrSkip(onEnter(n))) {
            cond.foreach(c ⇒ loop(c))
            dirs.foreach(d ⇒ loop(d))
            sels.foreach(s ⇒ loop(s))
            comment.foreach(s ⇒ loop(s))
            trailingComments.foreach(s ⇒ loop(s))
            breakOrSkip(onLeave(n))
          }
        case n @ FragmentSpread(_, dirs, comment, _) ⇒
          if (breakOrSkip(onEnter(n))) {
            dirs.foreach(d ⇒ loop(d))
            comment.foreach(s ⇒ loop(s))
            breakOrSkip(onLeave(n))
          }
        case n @ NotNullType(ofType, _) ⇒
          if (breakOrSkip(onEnter(n))) {
            loop(ofType)
            breakOrSkip(onLeave(n))
          }
        case n @ ListType(ofType, _) ⇒
          if (breakOrSkip(onEnter(n))) {
            loop(ofType)
            breakOrSkip(onLeave(n))
          }
        case n @ Field(_, _, args, dirs, sels, comment, trailingComments, _) ⇒
          if (breakOrSkip(onEnter(n))) {
            args.foreach(d ⇒ loop(d))
            dirs.foreach(d ⇒ loop(d))
            sels.foreach(s ⇒ loop(s))
            comment.foreach(s ⇒ loop(s))
            trailingComments.foreach(s ⇒ loop(s))
            breakOrSkip(onLeave(n))
          }
        case n @ Argument(_, v, comment, _) ⇒
          if (breakOrSkip(onEnter(n))) {
            loop(v)
            comment.foreach(s ⇒ loop(s))
            breakOrSkip(onLeave(n))
          }
        case n @ ObjectField(_, v, comment, _) ⇒
          if (breakOrSkip(onEnter(n))) {
            loop(v)
            comment.foreach(s ⇒ loop(s))
            breakOrSkip(onLeave(n))
          }
        case n @ Directive(_, args, comment, _) ⇒
          if (breakOrSkip(onEnter(n))) {
            args.foreach(d ⇒ loop(d))
            comment.foreach(s ⇒ loop(s))
            breakOrSkip(onLeave(n))
          }
        case n @ ListValue(vals, comment, _) ⇒
          if (breakOrSkip(onEnter(n))) {
            vals.foreach(v ⇒ loop(v))
            comment.foreach(s ⇒ loop(s))
            breakOrSkip(onLeave(n))
          }
        case n @ ObjectValue(fields, comment, _) ⇒
          if (breakOrSkip(onEnter(n))) {
            fields.foreach(f ⇒ loop(f))
            comment.foreach(s ⇒ loop(s))
            breakOrSkip(onLeave(n))
          }
        case n @ BigDecimalValue(_, comment, _) ⇒
          if (breakOrSkip(onEnter(n))) {
            comment.foreach(s ⇒ loop(s))
            breakOrSkip(onLeave(n))
          }
        case n @ BooleanValue(_, comment, _) ⇒
          if (breakOrSkip(onEnter(n))) {
            comment.foreach(s ⇒ loop(s))
            breakOrSkip(onLeave(n))
          }
        case n @ Comment(_, _) ⇒
          if (breakOrSkip(onEnter(n))) {
            breakOrSkip(onLeave(n))
          }
        case n @ VariableValue(_, comment, _) ⇒
          if (breakOrSkip(onEnter(n))) {
            comment.foreach(s ⇒ loop(s))
            breakOrSkip(onLeave(n))
          }
        case n @ EnumValue(_, comment, _) ⇒
          if (breakOrSkip(onEnter(n))) {
            comment.foreach(s ⇒ loop(s))
            breakOrSkip(onLeave(n))
          }
        case n @ NullValue(comment, _) ⇒
          if (breakOrSkip(onEnter(n))) {
            comment.foreach(s ⇒ loop(s))
            breakOrSkip(onLeave(n))
          }
        case n @ NamedType(_, _) ⇒
          if (breakOrSkip(onEnter(n))) {
            breakOrSkip(onLeave(n))
          }
        case n @ StringValue(_, comment, _) ⇒
          if (breakOrSkip(onEnter(n))) {
            comment.foreach(s ⇒ loop(s))
            breakOrSkip(onLeave(n))
          }
        case n @ BigIntValue(_, comment, _) ⇒
          if (breakOrSkip(onEnter(n))) {
            comment.foreach(s ⇒ loop(s))
            breakOrSkip(onLeave(n))
          }
        case n @ IntValue(_, comment, _) ⇒
          if (breakOrSkip(onEnter(n))) {
            comment.foreach(s ⇒ loop(s))
            breakOrSkip(onLeave(n))
          }
        case n @ FloatValue(_, comment, _) ⇒
          if (breakOrSkip(onEnter(n))) {
            comment.foreach(s ⇒ loop(s))
            breakOrSkip(onLeave(n))
          }

        // IDL schema definition

        case n @ ScalarTypeDefinition(_, dirs, comment, _) ⇒
          if (breakOrSkip(onEnter(n))) {
            dirs.foreach(d ⇒ loop(d))
            comment.foreach(s ⇒ loop(s))
            breakOrSkip(onLeave(n))
          }
        case n @ FieldDefinition(_, fieldType, args, dirs, comment, _) ⇒
          if (breakOrSkip(onEnter(n))) {
            loop(fieldType)
            args.foreach(d ⇒ loop(d))
            dirs.foreach(d ⇒ loop(d))
            comment.foreach(s ⇒ loop(s))
            breakOrSkip(onLeave(n))
          }
        case n @ InputValueDefinition(_, valueType, default, dirs, comment, _) ⇒
          if (breakOrSkip(onEnter(n))) {
            loop(valueType)
            default.foreach(d ⇒ loop(d))
            dirs.foreach(d ⇒ loop(d))
            comment.foreach(s ⇒ loop(s))
            breakOrSkip(onLeave(n))
          }
        case n @ ObjectTypeDefinition(_, interfaces, fields, dirs, comment, trailingComments, _) ⇒
          if (breakOrSkip(onEnter(n))) {
            interfaces.foreach(d ⇒ loop(d))
            fields.foreach(d ⇒ loop(d))
            dirs.foreach(d ⇒ loop(d))
            comment.foreach(s ⇒ loop(s))
            trailingComments.foreach(s ⇒ loop(s))
            breakOrSkip(onLeave(n))
          }
        case n @ InterfaceTypeDefinition(_, fields, dirs, comment, trailingComments, _) ⇒
          if (breakOrSkip(onEnter(n))) {
            fields.foreach(d ⇒ loop(d))
            dirs.foreach(d ⇒ loop(d))
            comment.foreach(s ⇒ loop(s))
            trailingComments.foreach(s ⇒ loop(s))
            breakOrSkip(onLeave(n))
          }
        case n @ UnionTypeDefinition(_, types, dirs, comment, _) ⇒
          if (breakOrSkip(onEnter(n))) {
            types.foreach(d ⇒ loop(d))
            dirs.foreach(d ⇒ loop(d))
            comment.foreach(s ⇒ loop(s))
            breakOrSkip(onLeave(n))
          }
        case n @ EnumTypeDefinition(_, values, dirs, comment, trailingComments, _) ⇒
          if (breakOrSkip(onEnter(n))) {
            values.foreach(d ⇒ loop(d))
            dirs.foreach(d ⇒ loop(d))
            comment.foreach(s ⇒ loop(s))
            trailingComments.foreach(s ⇒ loop(s))
            breakOrSkip(onLeave(n))
          }
        case n @ EnumValueDefinition(_, dirs, comment, _) ⇒
          if (breakOrSkip(onEnter(n))) {
            dirs.foreach(d ⇒ loop(d))
            comment.foreach(s ⇒ loop(s))
            breakOrSkip(onLeave(n))
          }
        case n @ InputObjectTypeDefinition(_, fields, dirs, comment, trailingComments, _) ⇒
          if (breakOrSkip(onEnter(n))) {
            fields.foreach(d ⇒ loop(d))
            dirs.foreach(d ⇒ loop(d))
            comment.foreach(s ⇒ loop(s))
            trailingComments.foreach(s ⇒ loop(s))
            breakOrSkip(onLeave(n))
          }
        case n @ TypeExtensionDefinition(definition, comment, _) ⇒
          if (breakOrSkip(onEnter(n))) {
            loop(definition)
            comment.foreach(s ⇒ loop(s))
            breakOrSkip(onLeave(n))
          }
        case n @ DirectiveDefinition(_, args, locations, comment, _) ⇒
          if (breakOrSkip(onEnter(n))) {
            args.foreach(d ⇒ loop(d))
            locations.foreach(d ⇒ loop(d))
            comment.foreach(s ⇒ loop(s))
            breakOrSkip(onLeave(n))
          }
        case n @ DirectiveLocation(_, comment, _) ⇒
          if (breakOrSkip(onEnter(n))) {
            comment.foreach(s ⇒ loop(s))
            breakOrSkip(onLeave(n))
          }
        case n @ SchemaDefinition(ops, dirs, comment, trailingComments, _) ⇒
          if (breakOrSkip(onEnter(n))) {
            ops.foreach(s ⇒ loop(s))
            dirs.foreach(s ⇒ loop(s))
            comment.foreach(s ⇒ loop(s))
            trailingComments.foreach(s ⇒ loop(s))
            breakOrSkip(onLeave(n))
          }
        case n @ OperationTypeDefinition(_, tpe, comment, _) ⇒
          if (breakOrSkip(onEnter(n))) {
            loop(tpe)
            comment.foreach(s ⇒ loop(s))
            breakOrSkip(onLeave(n))
          }
      }

    breakable {
      loop(doc)
    }
  }
}

object AstVisitorCommand extends Enumeration {
  val Skip, Continue, Break = Value

  val RightContinue: Either[Vector[Violation], AstVisitorCommand.Value] = Right(Continue)
  val RightSkip: Either[Vector[Violation], AstVisitorCommand.Value] = Right(Skip)
  val RightBreak: Either[Vector[Violation], AstVisitorCommand.Value] = Right(Break)
}
