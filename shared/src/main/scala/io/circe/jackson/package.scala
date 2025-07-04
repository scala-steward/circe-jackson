/*
 * Copyright 2016 circe
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.circe

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node._

import java.io._
import java.math.{ BigDecimal => JBigDecimal }
import java.nio.ByteBuffer
import scala.jdk.CollectionConverters._
import scala.collection.compat._

/**
 * Support for Jackson-powered parsing and printing for circe.
 *
 * Note that not all guarantees that hold for Jawn-based parsing and the default
 * printer will hold for the Jackson-based versions. Jackson's handling of
 * numbers in particular differs significantly: it doesn't distinguish positive
 * and negative zeros, it may truncate large JSON numbers or simply fail to
 * parse them, it may print large numbers as strings, etc.
 *
 * The implementation is ported with minimal changes from Play JSON.
 */
package object jackson extends WithJacksonMapper with JacksonParser with JacksonCompat {

  private[this] def writeJson(w: Writer, j: Json): Unit = {
    val gen = jsonGenerator(w)
    makeWriter(mapper).writeValue(gen, j)
    w.flush()
  }

  final def jacksonPrint(json: Json): String = {
    val sw = new StringWriter
    writeJson(sw, json)
    sw.toString
  }

  final def jacksonPrintByteBuffer(json: Json): ByteBuffer = {
    val bytes = new EnhancedByteArrayOutputStream
    writeJson(new BufferedWriter(new OutputStreamWriter(bytes, "UTF-8")), json)
    bytes.toByteBuffer
  }

  private val negativeZeroJson: Json = Json.fromDoubleOrNull(-0.0)

  /**
   * Converts given circe's Json instance to Jackson's JsonNode
   * Numbers with exponents exceeding Integer.MAX_VALUE are converted to strings
   * '''Warning: This implementation is not stack safe and will fail on very deep structures'''
   * @param json instance of circe's Json
   * @return converted JsonNode
   */
  final def circeToJackson(json: Json): JsonNode = json.fold(
    NullNode.instance,
    BooleanNode.valueOf(_),
    number =>
      if (json == negativeZeroJson) {
        DoubleNode.valueOf(number.toDouble)
      } else
        number match {
          case _: JsonBiggerDecimal | _: JsonBigDecimal =>
            number.toBigDecimal
              .map(bigDecimal => DecimalNode.valueOf(bigDecimal.underlying))
              .getOrElse(TextNode.valueOf(number.toString))
          case JsonLong(x)    => LongNode.valueOf(x)
          case JsonDouble(x)  => DoubleNode.valueOf(x)
          case JsonFloat(x)   => FloatNode.valueOf(x)
          case JsonDecimal(x) =>
            try {
              DecimalNode.valueOf(new JBigDecimal(x))
            } catch {
              case _: NumberFormatException => TextNode.valueOf(x)
            }
        },
    TextNode.valueOf(_),
    array => JsonNodeFactory.instance.arrayNode.addAll(array.map(circeToJackson(_)).asJava),
    obj =>
      objectNodeSetAll(JsonNodeFactory.instance.objectNode, obj.toMap.view.mapValues(circeToJackson(_)).toMap.asJava)
  )

  /**
   * Converts given Jackson's JsonNode to circe's Json
   * This implementation tries to keep the original numbers formatting
   * '''Warning: This implementation is not stack safe and will fail on very deep structures'''
   * @param node instance of Jackson's JsonNode
   * @return converted Json instance
   */
  final def jacksonToCirce(node: JsonNode): Json = node.getNodeType match {
    case JsonNodeType.BOOLEAN => Json.fromBoolean(node.asBoolean)
    case JsonNodeType.STRING  => Json.fromString(node.asText)
    case JsonNodeType.NUMBER  =>
      if (node.isFloatingPointNumber) {
        Json.fromBigDecimal(new JBigDecimal(node.asText)) // workaround for rounding problems
      } else {
        Json.fromBigInt(node.bigIntegerValue)
      }
    case JsonNodeType.ARRAY =>
      Json.fromValues(node.elements.asScala.map(jacksonToCirce(_)).to(Iterable))
    case JsonNodeType.OBJECT =>
      Json.fromFields(node.fields.asScala.map(m => (m.getKey, jacksonToCirce(m.getValue))).to(Iterable))
    case _ => Json.Null
  }

}

package jackson {
  private[jackson] class EnhancedByteArrayOutputStream extends ByteArrayOutputStream {
    def toByteBuffer: ByteBuffer = ByteBuffer.wrap(this.buf, 0, this.size)
  }
}
