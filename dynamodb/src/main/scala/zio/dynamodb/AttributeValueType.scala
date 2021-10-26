package zio.dynamodb

sealed trait AttributeValueType {
  // TODO(adam): Implement
  def render(): String = ???
}
sealed trait PrimitiveValueType extends AttributeValueType

object AttributeValueType {
  // primitive types
  case object Binary    extends PrimitiveValueType
  case object Number    extends PrimitiveValueType
  case object String    extends PrimitiveValueType
  // non primitive types
  case object Bool      extends AttributeValueType
  case object BinarySet extends AttributeValueType
  case object List      extends AttributeValueType
  case object Map       extends AttributeValueType
  case object NumberSet extends AttributeValueType
  case object Null      extends AttributeValueType
  case object StringSet extends AttributeValueType
}
