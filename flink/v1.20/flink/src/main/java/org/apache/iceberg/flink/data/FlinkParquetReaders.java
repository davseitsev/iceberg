/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iceberg.flink.data;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.flink.table.data.ArrayData;
import org.apache.flink.table.data.DecimalData;
import org.apache.flink.table.data.GenericArrayData;
import org.apache.flink.table.data.GenericRowData;
import org.apache.flink.table.data.MapData;
import org.apache.flink.table.data.RawValueData;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.data.StringData;
import org.apache.flink.table.data.TimestampData;
import org.apache.iceberg.MetadataColumns;
import org.apache.iceberg.Schema;
import org.apache.iceberg.parquet.ParquetSchemaUtil;
import org.apache.iceberg.parquet.ParquetValueReader;
import org.apache.iceberg.parquet.ParquetValueReaders;
import org.apache.iceberg.parquet.TypeWithSchemaVisitor;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableList;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableMap;
import org.apache.iceberg.relocated.com.google.common.collect.Lists;
import org.apache.iceberg.relocated.com.google.common.collect.Maps;
import org.apache.iceberg.types.Types;
import org.apache.iceberg.util.ArrayUtil;
import org.apache.parquet.column.ColumnDescriptor;
import org.apache.parquet.io.api.Binary;
import org.apache.parquet.schema.GroupType;
import org.apache.parquet.schema.LogicalTypeAnnotation;
import org.apache.parquet.schema.LogicalTypeAnnotation.DecimalLogicalTypeAnnotation;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType;
import org.apache.parquet.schema.Type;

public class FlinkParquetReaders {
  private FlinkParquetReaders() {}

  public static ParquetValueReader<RowData> buildReader(
      Schema expectedSchema, MessageType fileSchema) {
    return buildReader(expectedSchema, fileSchema, ImmutableMap.of());
  }

  @SuppressWarnings("unchecked")
  public static ParquetValueReader<RowData> buildReader(
      Schema expectedSchema, MessageType fileSchema, Map<Integer, ?> idToConstant) {
    return (ParquetValueReader<RowData>)
        TypeWithSchemaVisitor.visit(
            expectedSchema.asStruct(), fileSchema, new ReadBuilder(fileSchema, idToConstant));
  }

  private static class ReadBuilder extends TypeWithSchemaVisitor<ParquetValueReader<?>> {
    private final MessageType type;
    private final Map<Integer, ?> idToConstant;

    ReadBuilder(MessageType type, Map<Integer, ?> idToConstant) {
      this.type = type;
      this.idToConstant = idToConstant;
    }

    @Override
    public ParquetValueReader<RowData> message(
        Types.StructType expected, MessageType message, List<ParquetValueReader<?>> fieldReaders) {
      return struct(expected, message.asGroupType(), fieldReaders);
    }

    @Override
    @SuppressWarnings("checkstyle:CyclomaticComplexity")
    public ParquetValueReader<RowData> struct(
        Types.StructType expected, GroupType struct, List<ParquetValueReader<?>> fieldReaders) {
      // match the expected struct's order
      Map<Integer, ParquetValueReader<?>> readersById = Maps.newHashMap();
      Map<Integer, Type> typesById = Maps.newHashMap();
      Map<Integer, Integer> maxDefinitionLevelsById = Maps.newHashMap();
      List<Type> fields = struct.getFields();
      for (int i = 0; i < fields.size(); i += 1) {
        Type fieldType = fields.get(i);
        if (fieldReaders.get(i) != null) {
          int fieldD = type.getMaxDefinitionLevel(path(fieldType.getName())) - 1;
          if (fieldType.getId() != null) {
            int id = fieldType.getId().intValue();
            readersById.put(id, ParquetValueReaders.option(fieldType, fieldD, fieldReaders.get(i)));
            typesById.put(id, fieldType);
            if (idToConstant.containsKey(id)) {
              maxDefinitionLevelsById.put(id, fieldD);
            }
          }
        }
      }

      List<Types.NestedField> expectedFields =
          expected != null ? expected.fields() : ImmutableList.of();
      List<ParquetValueReader<?>> reorderedFields =
          Lists.newArrayListWithExpectedSize(expectedFields.size());
      // Defaulting to parent max definition level
      int defaultMaxDefinitionLevel = type.getMaxDefinitionLevel(currentPath());
      for (Types.NestedField field : expectedFields) {
        int id = field.fieldId();
        ParquetValueReader<?> reader = readersById.get(id);
        if (idToConstant.containsKey(id)) {
          // containsKey is used because the constant may be null
          int fieldMaxDefinitionLevel =
              maxDefinitionLevelsById.getOrDefault(id, defaultMaxDefinitionLevel);
          reorderedFields.add(
              ParquetValueReaders.constant(idToConstant.get(id), fieldMaxDefinitionLevel));
        } else if (id == MetadataColumns.ROW_POSITION.fieldId()) {
          reorderedFields.add(ParquetValueReaders.position());
        } else if (id == MetadataColumns.IS_DELETED.fieldId()) {
          reorderedFields.add(ParquetValueReaders.constant(false));
        } else if (reader != null) {
          reorderedFields.add(reader);
        } else if (field.initialDefault() != null) {
          reorderedFields.add(
              ParquetValueReaders.constant(
                  RowDataUtil.convertConstant(field.type(), field.initialDefault()),
                  maxDefinitionLevelsById.getOrDefault(id, defaultMaxDefinitionLevel)));
        } else if (field.isOptional()) {
          reorderedFields.add(ParquetValueReaders.nulls());
        } else {
          throw new IllegalArgumentException(
              String.format("Missing required field: %s", field.name()));
        }
      }

      return new RowDataReader(reorderedFields);
    }

    @Override
    public ParquetValueReader<?> list(
        Types.ListType expectedList, GroupType array, ParquetValueReader<?> elementReader) {
      if (expectedList == null) {
        return null;
      }

      String[] repeatedPath = currentPath();

      int repeatedD = type.getMaxDefinitionLevel(repeatedPath) - 1;
      int repeatedR = type.getMaxRepetitionLevel(repeatedPath) - 1;

      Type elementType = ParquetSchemaUtil.determineListElementType(array);
      int elementD = type.getMaxDefinitionLevel(path(elementType.getName())) - 1;

      return new ArrayReader<>(
          repeatedD, repeatedR, ParquetValueReaders.option(elementType, elementD, elementReader));
    }

    @Override
    public ParquetValueReader<?> map(
        Types.MapType expectedMap,
        GroupType map,
        ParquetValueReader<?> keyReader,
        ParquetValueReader<?> valueReader) {
      if (expectedMap == null) {
        return null;
      }

      GroupType repeatedKeyValue = map.getFields().get(0).asGroupType();
      String[] repeatedPath = currentPath();

      int repeatedD = type.getMaxDefinitionLevel(repeatedPath) - 1;
      int repeatedR = type.getMaxRepetitionLevel(repeatedPath) - 1;

      Type keyType = repeatedKeyValue.getType(0);
      int keyD = type.getMaxDefinitionLevel(path(keyType.getName())) - 1;
      Type valueType = repeatedKeyValue.getType(1);
      int valueD = type.getMaxDefinitionLevel(path(valueType.getName())) - 1;

      return new MapReader<>(
          repeatedD,
          repeatedR,
          ParquetValueReaders.option(keyType, keyD, keyReader),
          ParquetValueReaders.option(valueType, valueD, valueReader));
    }

    private static class LogicalTypeAnnotationParquetValueReaderVisitor
        implements LogicalTypeAnnotation.LogicalTypeAnnotationVisitor<ParquetValueReader<?>> {

      private final PrimitiveType primitive;
      private final ColumnDescriptor desc;
      private final org.apache.iceberg.types.Type.PrimitiveType expected;

      LogicalTypeAnnotationParquetValueReaderVisitor(
          PrimitiveType primitive,
          ColumnDescriptor desc,
          org.apache.iceberg.types.Type.PrimitiveType expected) {
        this.primitive = primitive;
        this.desc = desc;
        this.expected = expected;
      }

      @Override
      public Optional<ParquetValueReader<?>> visit(
          LogicalTypeAnnotation.StringLogicalTypeAnnotation stringLogicalType) {
        return Optional.of(new StringReader(desc));
      }

      @Override
      public Optional<ParquetValueReader<?>> visit(
          LogicalTypeAnnotation.EnumLogicalTypeAnnotation enumLogicalType) {
        return Optional.of(new StringReader(desc));
      }

      @Override
      public Optional<ParquetValueReader<?>> visit(
          LogicalTypeAnnotation.JsonLogicalTypeAnnotation jsonLogicalType) {
        return Optional.of(new StringReader(desc));
      }

      @Override
      public Optional<ParquetValueReader<?>> visit(
          DecimalLogicalTypeAnnotation decimalLogicalType) {
        switch (primitive.getPrimitiveTypeName()) {
          case BINARY:
          case FIXED_LEN_BYTE_ARRAY:
            return Optional.of(
                new BinaryDecimalReader(
                    desc, decimalLogicalType.getPrecision(), decimalLogicalType.getScale()));
          case INT64:
            return Optional.of(
                new LongDecimalReader(
                    desc, decimalLogicalType.getPrecision(), decimalLogicalType.getScale()));
          case INT32:
            return Optional.of(
                new IntegerDecimalReader(
                    desc, decimalLogicalType.getPrecision(), decimalLogicalType.getScale()));
        }

        return LogicalTypeAnnotation.LogicalTypeAnnotationVisitor.super.visit(decimalLogicalType);
      }

      @Override
      public Optional<ParquetValueReader<?>> visit(
          LogicalTypeAnnotation.DateLogicalTypeAnnotation dateLogicalType) {
        return Optional.of(new ParquetValueReaders.UnboxedReader<>(desc));
      }

      @Override
      public Optional<ParquetValueReader<?>> visit(
          LogicalTypeAnnotation.TimeLogicalTypeAnnotation timeLogicalType) {
        if (timeLogicalType.getUnit() == LogicalTypeAnnotation.TimeUnit.MILLIS) {
          return Optional.of(new MillisTimeReader(desc));
        } else if (timeLogicalType.getUnit() == LogicalTypeAnnotation.TimeUnit.MICROS) {
          return Optional.of(new LossyMicrosToMillisTimeReader(desc));
        }

        return LogicalTypeAnnotation.LogicalTypeAnnotationVisitor.super.visit(timeLogicalType);
      }

      @Override
      public Optional<ParquetValueReader<?>> visit(
          LogicalTypeAnnotation.TimestampLogicalTypeAnnotation timestampLogicalType) {
        if (timestampLogicalType.getUnit() == LogicalTypeAnnotation.TimeUnit.MILLIS) {
          return Optional.of(new MillisToTimestampReader(desc));
        } else if (timestampLogicalType.getUnit() == LogicalTypeAnnotation.TimeUnit.MICROS) {
          return Optional.of(new MicrosToTimestampReader(desc));
        } else if (timestampLogicalType.getUnit() == LogicalTypeAnnotation.TimeUnit.NANOS) {
          return Optional.of(new NanosToTimestampReader(desc));
        }

        return LogicalTypeAnnotation.LogicalTypeAnnotationVisitor.super.visit(timestampLogicalType);
      }

      @Override
      public Optional<ParquetValueReader<?>> visit(
          LogicalTypeAnnotation.IntLogicalTypeAnnotation intLogicalType) {
        int width = intLogicalType.getBitWidth();
        if (width <= 32) {
          if (expected.typeId() == Types.LongType.get().typeId()) {
            return Optional.of(new ParquetValueReaders.IntAsLongReader(desc));
          } else {
            return Optional.of(new ParquetValueReaders.UnboxedReader<>(desc));
          }
        } else if (width <= 64) {
          return Optional.of(new ParquetValueReaders.UnboxedReader<>(desc));
        }

        return LogicalTypeAnnotation.LogicalTypeAnnotationVisitor.super.visit(intLogicalType);
      }

      @Override
      public Optional<ParquetValueReader<?>> visit(
          LogicalTypeAnnotation.BsonLogicalTypeAnnotation bsonLogicalType) {
        return Optional.of(new ParquetValueReaders.ByteArrayReader(desc));
      }
    }

    @Override
    @SuppressWarnings("CyclomaticComplexity")
    public ParquetValueReader<?> primitive(
        org.apache.iceberg.types.Type.PrimitiveType expected, PrimitiveType primitive) {
      if (expected == null) {
        return null;
      }

      ColumnDescriptor desc = type.getColumnDescription(currentPath());
      LogicalTypeAnnotation logicalTypeAnnotation = primitive.getLogicalTypeAnnotation();
      if (logicalTypeAnnotation != null) {
        return logicalTypeAnnotation
            .accept(new LogicalTypeAnnotationParquetValueReaderVisitor(primitive, desc, expected))
            .orElseThrow(
                () ->
                    new UnsupportedOperationException(
                        "Unsupported logical type: " + primitive.getLogicalTypeAnnotation()));
      }

      switch (primitive.getPrimitiveTypeName()) {
        case FIXED_LEN_BYTE_ARRAY:
        case BINARY:
          return new ParquetValueReaders.ByteArrayReader(desc);
        case INT32:
          if (expected.typeId() == org.apache.iceberg.types.Type.TypeID.LONG) {
            return new ParquetValueReaders.IntAsLongReader(desc);
          } else {
            return new ParquetValueReaders.UnboxedReader<>(desc);
          }
        case FLOAT:
          if (expected.typeId() == org.apache.iceberg.types.Type.TypeID.DOUBLE) {
            return new ParquetValueReaders.FloatAsDoubleReader(desc);
          } else {
            return new ParquetValueReaders.UnboxedReader<>(desc);
          }
        case BOOLEAN:
        case INT64:
        case DOUBLE:
          return new ParquetValueReaders.UnboxedReader<>(desc);
        default:
          throw new UnsupportedOperationException("Unsupported type: " + primitive);
      }
    }
  }

  private static class BinaryDecimalReader
      extends ParquetValueReaders.PrimitiveReader<DecimalData> {
    private final int precision;
    private final int scale;

    BinaryDecimalReader(ColumnDescriptor desc, int precision, int scale) {
      super(desc);
      this.precision = precision;
      this.scale = scale;
    }

    @Override
    public DecimalData read(DecimalData ignored) {
      Binary binary = column.nextBinary();
      BigDecimal bigDecimal = new BigDecimal(new BigInteger(binary.getBytes()), scale);
      // TODO: need a unit test to write-read-validate decimal via FlinkParquetWrite/Reader
      return DecimalData.fromBigDecimal(bigDecimal, precision, scale);
    }
  }

  private static class IntegerDecimalReader
      extends ParquetValueReaders.PrimitiveReader<DecimalData> {
    private final int precision;
    private final int scale;

    IntegerDecimalReader(ColumnDescriptor desc, int precision, int scale) {
      super(desc);
      this.precision = precision;
      this.scale = scale;
    }

    @Override
    public DecimalData read(DecimalData ignored) {
      return DecimalData.fromUnscaledLong(column.nextInteger(), precision, scale);
    }
  }

  private static class LongDecimalReader extends ParquetValueReaders.PrimitiveReader<DecimalData> {
    private final int precision;
    private final int scale;

    LongDecimalReader(ColumnDescriptor desc, int precision, int scale) {
      super(desc);
      this.precision = precision;
      this.scale = scale;
    }

    @Override
    public DecimalData read(DecimalData ignored) {
      return DecimalData.fromUnscaledLong(column.nextLong(), precision, scale);
    }
  }

  private static class NanosToTimestampReader
      extends ParquetValueReaders.UnboxedReader<TimestampData> {
    NanosToTimestampReader(ColumnDescriptor desc) {
      super(desc);
    }

    @Override
    public TimestampData read(TimestampData ignored) {
      long value = readLong();
      return TimestampData.fromEpochMillis(
          Math.floorDiv(value, 1_000_000L), Math.floorMod(value, 1_000_000));
    }
  }

  private static class MicrosToTimestampReader
      extends ParquetValueReaders.UnboxedReader<TimestampData> {
    MicrosToTimestampReader(ColumnDescriptor desc) {
      super(desc);
    }

    @Override
    public TimestampData read(TimestampData ignored) {
      long micros = readLong();
      return TimestampData.fromEpochMillis(
          Math.floorDiv(micros, 1000L), Math.floorMod(micros, 1000) * 1000);
    }
  }

  private static class MillisToTimestampReader
      extends ParquetValueReaders.UnboxedReader<TimestampData> {
    MillisToTimestampReader(ColumnDescriptor desc) {
      super(desc);
    }

    @Override
    public TimestampData read(TimestampData ignored) {
      long millis = readLong();
      return TimestampData.fromEpochMillis(millis);
    }
  }

  private static class StringReader extends ParquetValueReaders.PrimitiveReader<StringData> {
    StringReader(ColumnDescriptor desc) {
      super(desc);
    }

    @Override
    public StringData read(StringData ignored) {
      Binary binary = column.nextBinary();
      ByteBuffer buffer = binary.toByteBuffer();
      if (buffer.hasArray()) {
        return StringData.fromBytes(
            buffer.array(), buffer.arrayOffset() + buffer.position(), buffer.remaining());
      } else {
        return StringData.fromBytes(binary.getBytes());
      }
    }
  }

  private static class LossyMicrosToMillisTimeReader
      extends ParquetValueReaders.PrimitiveReader<Integer> {
    LossyMicrosToMillisTimeReader(ColumnDescriptor desc) {
      super(desc);
    }

    @Override
    public Integer read(Integer reuse) {
      // Discard microseconds since Flink uses millisecond unit for TIME type.
      return (int) Math.floorDiv(column.nextLong(), 1000L);
    }
  }

  private static class MillisTimeReader extends ParquetValueReaders.PrimitiveReader<Integer> {
    MillisTimeReader(ColumnDescriptor desc) {
      super(desc);
    }

    @Override
    public Integer read(Integer reuse) {
      return (int) column.nextLong();
    }
  }

  private static class ArrayReader<E>
      extends ParquetValueReaders.RepeatedReader<ArrayData, ReusableArrayData, E> {
    private int readPos = 0;
    private int writePos = 0;

    ArrayReader(int definitionLevel, int repetitionLevel, ParquetValueReader<E> reader) {
      super(definitionLevel, repetitionLevel, reader);
    }

    @Override
    protected ReusableArrayData newListData(ArrayData reuse) {
      this.readPos = 0;
      this.writePos = 0;

      if (reuse instanceof ReusableArrayData) {
        return (ReusableArrayData) reuse;
      } else {
        return new ReusableArrayData();
      }
    }

    @Override
    @SuppressWarnings("unchecked")
    protected E getElement(ReusableArrayData list) {
      E value = null;
      if (readPos < list.capacity()) {
        value = (E) list.values[readPos];
      }

      readPos += 1;

      return value;
    }

    @Override
    protected void addElement(ReusableArrayData reused, E element) {
      if (writePos >= reused.capacity()) {
        reused.grow();
      }

      reused.values[writePos] = element;

      writePos += 1;
    }

    @Override
    protected ArrayData buildList(ReusableArrayData list) {
      // Since ReusableArrayData is not accepted by Flink, use GenericArrayData temporarily to walk
      // around it.
      // Revert this to use ReusableArrayData once it is fixed in Flink.
      // For your reference, https://issues.apache.org/jira/browse/FLINK-25238.
      return new GenericArrayData(Arrays.copyOf(list.values, writePos));
    }
  }

  private static class MapReader<K, V>
      extends ParquetValueReaders.RepeatedKeyValueReader<MapData, ReusableMapData, K, V> {
    private int readPos = 0;
    private int writePos = 0;

    private final ParquetValueReaders.ReusableEntry<K, V> entry =
        new ParquetValueReaders.ReusableEntry<>();
    private final ParquetValueReaders.ReusableEntry<K, V> nullEntry =
        new ParquetValueReaders.ReusableEntry<>();

    MapReader(
        int definitionLevel,
        int repetitionLevel,
        ParquetValueReader<K> keyReader,
        ParquetValueReader<V> valueReader) {
      super(definitionLevel, repetitionLevel, keyReader, valueReader);
    }

    @Override
    protected ReusableMapData newMapData(MapData reuse) {
      this.readPos = 0;
      this.writePos = 0;

      if (reuse instanceof ReusableMapData) {
        return (ReusableMapData) reuse;
      } else {
        return new ReusableMapData();
      }
    }

    @Override
    @SuppressWarnings("unchecked")
    protected Map.Entry<K, V> getPair(ReusableMapData map) {
      Map.Entry<K, V> kv = nullEntry;
      if (readPos < map.capacity()) {
        entry.set((K) map.keys.values[readPos], (V) map.values.values[readPos]);
        kv = entry;
      }

      readPos += 1;

      return kv;
    }

    @Override
    protected void addPair(ReusableMapData map, K key, V value) {
      if (writePos >= map.capacity()) {
        map.grow();
      }

      map.keys.values[writePos] = key;
      map.values.values[writePos] = value;

      writePos += 1;
    }

    @Override
    protected MapData buildMap(ReusableMapData map) {
      map.setNumElements(writePos);
      return map;
    }
  }

  private static class RowDataReader
      extends ParquetValueReaders.StructReader<RowData, GenericRowData> {
    private final int numFields;

    RowDataReader(List<ParquetValueReader<?>> readers) {
      super(readers);
      this.numFields = readers.size();
    }

    @Override
    protected GenericRowData newStructData(RowData reuse) {
      if (reuse instanceof GenericRowData) {
        return (GenericRowData) reuse;
      } else {
        return new GenericRowData(numFields);
      }
    }

    @Override
    protected Object getField(GenericRowData intermediate, int pos) {
      return intermediate.getField(pos);
    }

    @Override
    protected RowData buildStruct(GenericRowData struct) {
      return struct;
    }

    @Override
    protected void set(GenericRowData row, int pos, Object value) {
      row.setField(pos, value);
    }

    @Override
    protected void setNull(GenericRowData row, int pos) {
      row.setField(pos, null);
    }

    @Override
    protected void setBoolean(GenericRowData row, int pos, boolean value) {
      row.setField(pos, value);
    }

    @Override
    protected void setInteger(GenericRowData row, int pos, int value) {
      row.setField(pos, value);
    }

    @Override
    protected void setLong(GenericRowData row, int pos, long value) {
      row.setField(pos, value);
    }

    @Override
    protected void setFloat(GenericRowData row, int pos, float value) {
      row.setField(pos, value);
    }

    @Override
    protected void setDouble(GenericRowData row, int pos, double value) {
      row.setField(pos, value);
    }
  }

  private static class ReusableMapData implements MapData {
    private final ReusableArrayData keys;
    private final ReusableArrayData values;

    private int numElements;

    private ReusableMapData() {
      this.keys = new ReusableArrayData();
      this.values = new ReusableArrayData();
    }

    private void grow() {
      keys.grow();
      values.grow();
    }

    private int capacity() {
      return keys.capacity();
    }

    public void setNumElements(int numElements) {
      this.numElements = numElements;
      keys.setNumElements(numElements);
      values.setNumElements(numElements);
    }

    @Override
    public int size() {
      return numElements;
    }

    @Override
    public ReusableArrayData keyArray() {
      return keys;
    }

    @Override
    public ReusableArrayData valueArray() {
      return values;
    }
  }

  private static class ReusableArrayData implements ArrayData {
    private static final Object[] EMPTY = new Object[0];

    private Object[] values = EMPTY;
    private int numElements = 0;

    private void grow() {
      if (values.length == 0) {
        this.values = new Object[20];
      } else {
        Object[] old = values;
        this.values = new Object[old.length << 1];
        // copy the old array in case it has values that can be reused
        System.arraycopy(old, 0, values, 0, old.length);
      }
    }

    private int capacity() {
      return values.length;
    }

    public void setNumElements(int numElements) {
      this.numElements = numElements;
    }

    @Override
    public int size() {
      return numElements;
    }

    @Override
    public boolean isNullAt(int ordinal) {
      return null == values[ordinal];
    }

    @Override
    public boolean getBoolean(int ordinal) {
      return (boolean) values[ordinal];
    }

    @Override
    public byte getByte(int ordinal) {
      return (byte) values[ordinal];
    }

    @Override
    public short getShort(int ordinal) {
      return (short) values[ordinal];
    }

    @Override
    public int getInt(int ordinal) {
      return (int) values[ordinal];
    }

    @Override
    public long getLong(int ordinal) {
      return (long) values[ordinal];
    }

    @Override
    public float getFloat(int ordinal) {
      return (float) values[ordinal];
    }

    @Override
    public double getDouble(int ordinal) {
      return (double) values[ordinal];
    }

    @Override
    public StringData getString(int pos) {
      return (StringData) values[pos];
    }

    @Override
    public DecimalData getDecimal(int pos, int precision, int scale) {
      return (DecimalData) values[pos];
    }

    @Override
    public TimestampData getTimestamp(int pos, int precision) {
      return (TimestampData) values[pos];
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> RawValueData<T> getRawValue(int pos) {
      return (RawValueData<T>) values[pos];
    }

    @Override
    public byte[] getBinary(int ordinal) {
      return (byte[]) values[ordinal];
    }

    @Override
    public ArrayData getArray(int ordinal) {
      return (ArrayData) values[ordinal];
    }

    @Override
    public MapData getMap(int ordinal) {
      return (MapData) values[ordinal];
    }

    @Override
    public RowData getRow(int pos, int numFields) {
      return (RowData) values[pos];
    }

    @Override
    public boolean[] toBooleanArray() {
      return ArrayUtil.toPrimitive((Boolean[]) values);
    }

    @Override
    public byte[] toByteArray() {
      return ArrayUtil.toPrimitive((Byte[]) values);
    }

    @Override
    public short[] toShortArray() {
      return ArrayUtil.toPrimitive((Short[]) values);
    }

    @Override
    public int[] toIntArray() {
      return ArrayUtil.toPrimitive((Integer[]) values);
    }

    @Override
    public long[] toLongArray() {
      return ArrayUtil.toPrimitive((Long[]) values);
    }

    @Override
    public float[] toFloatArray() {
      return ArrayUtil.toPrimitive((Float[]) values);
    }

    @Override
    public double[] toDoubleArray() {
      return ArrayUtil.toPrimitive((Double[]) values);
    }
  }
}
