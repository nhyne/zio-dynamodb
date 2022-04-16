package zio.dynamodb.codec

import zio.dynamodb.Codec
import zio.schema.{ DeriveSchema, Schema, StandardType }
import zio.test.Assertion.{ equalTo, isRight }
import zio.test._
import zio.{ Chunk, ZIO }

import scala.collection.immutable.ListMap
import zio.test.{ Gen, Sized, ZIOSpecDefault }

object CodecRoundTripSpec extends ZIOSpecDefault with CodecTestFixtures {

  override def spec =
    suite("encode then decode suite")(
      simpleSuite,
      eitherSuite,
      optionalSuite,
      caseClassSuite,
      recordSuite,
      sequenceSuite,
      enumerationSuite,
      transformSuite,
      anySchemaSuite
    )

  private val eitherSuite = suite("either suite")(
    test("a primitive") {
      check(SchemaGen.anyEitherAndGen) {
        case (schema, gen) =>
          assertEncodesThenDecodesWithGen(schema, gen)
      }
    },
    test("of tuples") {
      check(
        for {
          left  <- SchemaGen.anyTupleAndValue
          right <- SchemaGen.anyTupleAndValue
        } yield (
          Schema.EitherSchema(left._1.asInstanceOf[Schema[(Any, Any)]], right._1.asInstanceOf[Schema[(Any, Any)]]),
          Right(right._2)
        )
      ) {
        case (schema, value) => assertEncodesThenDecodes(schema, value)
      }
    },
    test("of sequence") {
      check(
        for {
          left  <- SchemaGen.anySequenceAndValue
          right <- SchemaGen.anySequenceAndValue
        } yield (
          Schema.EitherSchema(left._1.asInstanceOf[Schema[Chunk[Any]]], right._1.asInstanceOf[Schema[Chunk[Any]]]),
          Left(left._2)
        )
      ) {
        case (schema, value) => assertEncodesThenDecodes(schema, value)
      }
    },
    test("of records") {
      check(for {
        (left, a)       <- SchemaGen.anyRecordAndValue
        primitiveSchema <- SchemaGen.anyPrimitive
      } yield (Schema.EitherSchema(left, primitiveSchema), Left(a))) {
        case (schema, value) => assertEncodesThenDecodes(schema, value)
      }
    },
    test("of records of records") {
      check(for {
        (left, _)  <- SchemaGen.anyRecordOfRecordsAndValue
        (right, b) <- SchemaGen.anyRecordOfRecordsAndValue
      } yield (Schema.EitherSchema(left, right), Right(b))) {
        case (schema, value) =>
          assertEncodesThenDecodes(schema, value)
      }
    },
    test("mixed") {
      check(for {
        (left, _)      <- SchemaGen.anyEnumerationAndValue
        (right, value) <- SchemaGen.anySequenceAndValue
      } yield (Schema.EitherSchema(left, right), Right(value))) {
        case (schema, value) => assertEncodesThenDecodes(schema, value)
      }
    }
  )

  private val optionalSuite = suite("optional suite")(
    test("of primitive") {
      check(SchemaGen.anyOptionalAndValue) {
        case (schema, value) => assertEncodesThenDecodes(schema, value)
      }
    },
    test("of tuple") {
      check(SchemaGen.anyTupleAndValue) {
        case (schema, value) =>
          assertEncodesThenDecodes(Schema.Optional(schema), Some(value)) &>
            assertEncodesThenDecodes(Schema.Optional(schema), None)
      }
    },
    test("of record") {
      check(SchemaGen.anyRecordAndValue) {
        case (schema, value) =>
          assertEncodesThenDecodes(Schema.Optional(schema), Some(value)) &>
            assertEncodesThenDecodes(Schema.Optional(schema), None)
      }
    },
    test("of enumeration") {
      check(SchemaGen.anyEnumerationAndValue) {
        case (schema, value) =>
          assertEncodesThenDecodes(Schema.Optional(schema), Some(value)) &>
            assertEncodesThenDecodes(Schema.Optional(schema), None)
      }
    },
    test("of sequence") {
      check(SchemaGen.anySequenceAndValue) {
        case (schema, value) =>
          assertEncodesThenDecodes(Schema.Optional(schema), Some(value)) &>
            assertEncodesThenDecodes(Schema.Optional(schema), None)
      }
    }
  )

  private val sequenceSuite = suite("sequence")(
    test("of primitives") {
      check(SchemaGen.anySequenceAndValue) {
        case (schema, value) => assertEncodesThenDecodes(schema, value)
      }
    },
    test("of records") {
      check(SchemaGen.anyCaseClassAndValue) {
        case (schema, value) =>
          assertEncodesThenDecodes(Schema.chunk(schema), Chunk.fill(3)(value))
      }
    },
    test("of java.time.ZoneOffset") {
      //FIXME test independently because including ZoneOffset in StandardTypeGen.anyStandardType wreaks havoc.
      check(Gen.chunkOf(JavaTimeGen.anyZoneOffset)) { chunk =>
        assertEncodesThenDecodes(
          Schema.chunk(Schema.Primitive(StandardType.ZoneOffsetType)),
          chunk
        )
      }
    }
  )

  private val caseClassSuite = suite("case class")(
    test("basic") {
      check(searchRequestGen) { value =>
        assertEncodesThenDecodes(searchRequestSchema, value)
      }
    },
    test("object") {
      assertEncodesThenDecodes(schemaObject, Singleton)
    }
  )

  private val recordSuite = suite("record")(
    test("any") {
      check(SchemaGen.anyRecordAndValue) {
        case (schema, value) => assertEncodesThenDecodes(schema, value)
      }
    },
    test("minimal test case") {
      SchemaGen.anyRecordAndValue.runHead.flatMap {
        case Some((schema, value)) =>
          val key      = new String(Array('\u0007', '\n'))
          val embedded = Schema.record(Schema.Field(key, schema))
          assertEncodesThenDecodes(embedded, ListMap(key -> value))
        case None                  => ZIO.fail("Should never happen!")
      }
    },
    test("record of records") {
      check(SchemaGen.anyRecordOfRecordsAndValue) {
        case (schema, value) =>
          assertEncodesThenDecodes(schema, value)
      }
    },
    test("of primitives") {
      check(SchemaGen.anyRecordAndValue) {
        case (schema, value) => assertEncodesThenDecodes(schema, value)
      }
    },
    test("of ZoneOffsets") {
      check(JavaTimeGen.anyZoneOffset) { zoneOffset =>
        assertEncodesThenDecodes(
          Schema.record(Schema.Field("zoneOffset", Schema.Primitive(StandardType.ZoneOffsetType))),
          ListMap[String, Any]("zoneOffset" -> zoneOffset)
        )
      }
    },
    test("of record") {
      assertEncodesThenDecodes(
        nestedRecordSchema,
        ListMap[String, Any]("l1" -> "s", "l2" -> ListMap[String, Any]("foo" -> "s", "bar" -> 1))
      )
    }
  )

  private val enumerationSuite = suite("enumeration")(
    test("of primitives") {
      assertEncodesThenDecodes(
        enumSchema,
        "foo"
      )
    },
    test("ADT") {
      assertEncodesThenDecodes(
        Schema[Enumeration],
        Enumeration(StringValue("foo"))
      ) &> assertEncodesThenDecodes(Schema[Enumeration], Enumeration(IntValue(-1))) &> assertEncodesThenDecodes(
        Schema[Enumeration],
        Enumeration(BooleanValue(false))
      )
    }
  )

  private val transformSuite = suite("transform")(
    test("any") {
      check(SchemaGen.anyTransformAndValue) {
        case (schema, value) =>
          assertEncodesThenDecodes(schema, value)
      }
    }
  )

  private val simpleSuite = suite("simple suite")(
    test("unit") {
      assertEncodesThenDecodesPure(Schema[Unit], ())
    },
    test("a primitive") {
      check(SchemaGen.anyPrimitiveAndGen) {
        case (schema, gen) =>
          assertEncodesThenDecodesWithGen(schema, gen)
      }
    },
    test("either of primitive") {
      check(SchemaGen.anyEitherAndGen) {
        case (schema, gen) =>
          assertEncodesThenDecodesWithGen(schema, gen)
      }
    },
    test("of enumeration") {
      check(SchemaGen.anyEnumerationAndGen) {
        case (schema, gen) =>
          assertEncodesThenDecodesWithGen(schema, gen)
      }
    },
    test("optional of primitive") {
      check(SchemaGen.anyOptionalAndGen) {
        case (schema, gen) =>
          assertEncodesThenDecodesWithGen(schema, gen)
      }
    },
    test("tuple of primitive") {
      check(SchemaGen.anyTupleAndGen) {
        case (schema, gen) =>
          assertEncodesThenDecodesWithGen(schema, gen)
      }
    },
    test("sequence of primitive") {
      check(SchemaGen.anySequenceAndGen) {
        case (schema, gen) =>
          assertEncodesThenDecodesWithGen(schema, gen)
      }
    },
    test("Map of string to primitive value") {
      check(SchemaGen.anyPrimitiveAndGen) {
        case (s, gen) =>
          val mapSchema = Schema.map(Schema[String], s)
          val enc       = Codec.encoder(mapSchema)
          val dec       = Codec.decoder(mapSchema)

          check(gen) { a =>
            val initialMap = Map("StringKey" -> a)
            val encoded    = enc(initialMap)
            val decoded    = dec(encoded)
            assert(decoded)(isRight(equalTo(initialMap)))
          }
      }
    },
    test("any Map") {
      check(SchemaGen.anyMapAndValue) {
        case (schema, value) =>
          assertEncodesThenDecodes(schema, value)
      }
    }
  )

  private val anySchemaSuite = suite("any schema")(
    test("leaf") {
      check(SchemaGen.anyLeafAndValue) {
        case (schema, value) =>
          assertEncodesThenDecodes(schema, value)
      }
    },
    test("recursive schema") {
      check(SchemaGen.anyTreeAndValue) {
        case (schema, value) =>
          assertEncodesThenDecodes(schema, value)
      }
    },
    test("recursive data type") {
      check(SchemaGen.anyRecursiveTypeAndValue) {
        case (schema, value) =>
          assertEncodesThenDecodes(schema, value)
      }
    }
  )

  private def assertEncodesThenDecodesWithGen[A](schema: Schema[A], genA: Gen[Sized, A]) =
    check(genA) { a =>
      assertEncodesThenDecodesPure(schema, a)
    }

  private def assertEncodesThenDecodesPure[A](schema: Schema[A], a: A) = {
    val enc = Codec.encoder(schema)
    val dec = Codec.decoder(schema)

    val encoded = enc(a)
    val decoded = dec(encoded)

    assert(decoded)(isRight(equalTo(a)))
  }

  private def assertEncodesThenDecodes[A](schema: Schema[A], a: A) =
    ZIO.succeed(assertEncodesThenDecodesPure(schema, a))

  case class SearchRequest(query: String, pageNumber: Int, resultPerPage: Int)

  val searchRequestGen: Gen[Sized, SearchRequest] =
    for {
      query      <- Gen.string
      pageNumber <- Gen.int(Int.MinValue, Int.MaxValue)
      results    <- Gen.int(Int.MinValue, Int.MaxValue)
    } yield SearchRequest(query, pageNumber, results)

  val searchRequestSchema: Schema[SearchRequest] = DeriveSchema.gen[SearchRequest]

  sealed trait OneOf
  final case class StringValue(value: String)   extends OneOf
  final case class IntValue(value: Int)         extends OneOf
  final case class BooleanValue(value: Boolean) extends OneOf

  object OneOf {
    implicit val schema: Schema[OneOf] = DeriveSchema.gen[OneOf]
  }

  final case class Enumeration(oneOf: OneOf)
  object Enumeration {
    implicit val schema: Schema[Enumeration] = DeriveSchema.gen[Enumeration]
  }

  case object Singleton
  implicit val schemaObject: Schema[Singleton.type] = DeriveSchema.gen[Singleton.type]

  val nestedRecordSchema: Schema[ListMap[String, _]] = Schema.record(
    Schema.Field("l1", Schema.Primitive(StandardType.StringType)),
    Schema.Field("l2", recordSchema)
  )

  final case class Value(first: Int, second: Boolean)
  object Value {
    implicit lazy val schema: Schema[Value] = DeriveSchema.gen[Value]
  }
}
