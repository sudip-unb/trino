/*
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
package io.trino.plugin.hive.metastore.glue.converter;

import com.amazonaws.services.glue.model.SerDeInfo;
import com.amazonaws.services.glue.model.StorageDescriptor;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.trino.plugin.hive.HiveBucketProperty;
import io.trino.plugin.hive.HiveStorageFormat;
import io.trino.plugin.hive.HiveType;
import io.trino.plugin.hive.metastore.Column;
import io.trino.plugin.hive.metastore.Database;
import io.trino.plugin.hive.metastore.Partition;
import io.trino.plugin.hive.metastore.SortingColumn;
import io.trino.plugin.hive.metastore.SortingColumn.Order;
import io.trino.plugin.hive.metastore.Storage;
import io.trino.plugin.hive.metastore.StorageFormat;
import io.trino.plugin.hive.metastore.Table;
import io.trino.plugin.hive.util.HiveBucketing;
import io.trino.plugin.hive.util.HiveBucketing.BucketingVersion;
import io.trino.spi.TrinoException;
import io.trino.spi.connector.SchemaTableName;
import io.trino.spi.security.PrincipalType;
import org.gaul.modernizer_maven_annotations.SuppressModernizer;

import javax.annotation.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Strings.emptyToNull;
import static com.google.common.base.Strings.nullToEmpty;
import static io.trino.plugin.hive.HiveErrorCode.HIVE_INVALID_METADATA;
import static io.trino.plugin.hive.HiveErrorCode.HIVE_UNSUPPORTED_FORMAT;
import static io.trino.plugin.hive.HiveType.HIVE_INT;
import static io.trino.plugin.hive.TableType.EXTERNAL_TABLE;
import static io.trino.plugin.hive.metastore.util.Memoizers.memoizeLast;
import static io.trino.plugin.hive.util.HiveUtil.isDeltaLakeTable;
import static io.trino.plugin.hive.util.HiveUtil.isIcebergTable;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

public final class GlueToTrinoConverter
{
    private static final String PUBLIC_OWNER = "PUBLIC";

    private GlueToTrinoConverter() {}

    public static String getTableType(com.amazonaws.services.glue.model.Table glueTable)
    {
        // Athena treats missing table type as EXTERNAL_TABLE.
        return firstNonNull(getTableTypeNullable(glueTable), EXTERNAL_TABLE.name());
    }

    @Nullable
    @SuppressModernizer // Usage of `Table.getTableType` is not allowed. Only this method can call that.
    public static String getTableTypeNullable(com.amazonaws.services.glue.model.Table glueTable)
    {
        return glueTable.getTableType();
    }

    public static Database convertDatabase(com.amazonaws.services.glue.model.Database glueDb)
    {
        return Database.builder()
                .setDatabaseName(glueDb.getName())
                // Currently it's not possible to create a Glue database with empty location string ""
                // (validation error detected: Value '' at 'database.locationUri' failed to satisfy constraint: Member must have length greater than or equal to 1)
                // However, it has been observed that Glue databases with empty location do exist in the wild.
                .setLocation(Optional.ofNullable(emptyToNull(glueDb.getLocationUri())))
                .setComment(Optional.ofNullable(glueDb.getDescription()))
                .setParameters(firstNonNull(glueDb.getParameters(), ImmutableMap.of()))
                .setOwnerName(Optional.of(PUBLIC_OWNER))
                .setOwnerType(Optional.of(PrincipalType.ROLE))
                .build();
    }

    public static Table convertTable(com.amazonaws.services.glue.model.Table glueTable, String dbName)
    {
        SchemaTableName table = new SchemaTableName(dbName, glueTable.getName());

        Map<String, String> tableParameters = convertParameters(glueTable.getParameters());
        Table.Builder tableBuilder = Table.builder()
                .setDatabaseName(table.getSchemaName())
                .setTableName(table.getTableName())
                .setOwner(Optional.ofNullable(glueTable.getOwner()))
                .setTableType(getTableType(glueTable))
                .setParameters(tableParameters)
                .setViewOriginalText(Optional.ofNullable(glueTable.getViewOriginalText()))
                .setViewExpandedText(Optional.ofNullable(glueTable.getViewExpandedText()));

        StorageDescriptor sd = glueTable.getStorageDescriptor();

        if (isIcebergTable(tableParameters) || (sd == null && isDeltaLakeTable(tableParameters))) {
            // Iceberg tables do not need to read the StorageDescriptor field, but we still need to return dummy properties for compatibility
            // Delta Lake tables only need to provide a dummy properties if a StorageDescriptor was not explicitly configured.
            tableBuilder.setDataColumns(ImmutableList.of(new Column("dummy", HIVE_INT, Optional.empty())));
            tableBuilder.getStorageBuilder().setStorageFormat(StorageFormat.fromHiveStorageFormat(HiveStorageFormat.PARQUET));
        }
        else {
            if (sd == null) {
                throw new TrinoException(HIVE_UNSUPPORTED_FORMAT, "Table StorageDescriptor is null for table '%s' %s".formatted(table, glueTable));
            }
            tableBuilder.setDataColumns(convertColumns(table, sd.getColumns(), sd.getSerdeInfo().getSerializationLibrary()));
            if (glueTable.getPartitionKeys() != null) {
                tableBuilder.setPartitionColumns(convertColumns(table, glueTable.getPartitionKeys(), sd.getSerdeInfo().getSerializationLibrary()));
            }
            else {
                tableBuilder.setPartitionColumns(ImmutableList.of());
            }
            // No benefit to memoizing here, just reusing the implementation
            new StorageConverter().setStorageBuilder(sd, tableBuilder.getStorageBuilder(), tableParameters);
        }

        return tableBuilder.build();
    }

    private static Column convertColumn(SchemaTableName table, com.amazonaws.services.glue.model.Column glueColumn, String serde)
    {
        // OpenCSVSerde deserializes columns from csv file into strings, so we set the column type from the metastore
        // to string to avoid cast exceptions.
        if (HiveStorageFormat.CSV.getSerde().equals(serde)) {
            //TODO(https://github.com/trinodb/trino/issues/7240) Add tests
            return new Column(glueColumn.getName(), HiveType.HIVE_STRING, Optional.ofNullable(glueColumn.getComment()));
        }
        return new Column(glueColumn.getName(), convertType(table, glueColumn), Optional.ofNullable(glueColumn.getComment()));
    }

    private static HiveType convertType(SchemaTableName table, com.amazonaws.services.glue.model.Column column)
    {
        try {
            return HiveType.valueOf(column.getType().toLowerCase(Locale.ENGLISH));
        }
        catch (IllegalArgumentException e) {
            throw new TrinoException(HIVE_INVALID_METADATA, "Glue table '%s' column '%s' has invalid data type: %s".formatted(table, column.getName(), column.getType()));
        }
    }

    private static List<Column> convertColumns(SchemaTableName table, List<com.amazonaws.services.glue.model.Column> glueColumns, String serde)
    {
        return mappedCopy(glueColumns, glueColumn -> convertColumn(table, glueColumn, serde));
    }

    private static Map<String, String> convertParameters(Map<String, String> parameters)
    {
        if (parameters == null || parameters.isEmpty()) {
            return ImmutableMap.of();
        }
        return ImmutableMap.copyOf(parameters);
    }

    private static Function<Map<String, String>, Map<String, String>> parametersConverter()
    {
        return memoizeLast(GlueToTrinoConverter::convertParameters);
    }

    private static boolean isNullOrEmpty(List<?> list)
    {
        return list == null || list.isEmpty();
    }

    public static final class GluePartitionConverter
            implements Function<com.amazonaws.services.glue.model.Partition, Partition>
    {
        private final Function<List<com.amazonaws.services.glue.model.Column>, List<Column>> columnsConverter;
        private final Function<Map<String, String>, Map<String, String>> parametersConverter = parametersConverter();
        private final StorageConverter storageConverter = new StorageConverter();
        private final String databaseName;
        private final String tableName;
        private final Map<String, String> tableParameters;

        public GluePartitionConverter(Table table)
        {
            requireNonNull(table, "table is null");
            this.databaseName = requireNonNull(table.getDatabaseName(), "databaseName is null");
            this.tableName = requireNonNull(table.getTableName(), "tableName is null");
            this.tableParameters = convertParameters(table.getParameters());
            this.columnsConverter = memoizeLast(glueColumns -> convertColumns(
                    table.getSchemaTableName(),
                    glueColumns,
                    table.getStorage().getStorageFormat().getSerde()));
        }

        @Override
        public Partition apply(com.amazonaws.services.glue.model.Partition gluePartition)
        {
            requireNonNull(gluePartition.getStorageDescriptor(), "Partition StorageDescriptor is null");
            StorageDescriptor sd = gluePartition.getStorageDescriptor();

            if (!databaseName.equals(gluePartition.getDatabaseName())) {
                throw new IllegalArgumentException(format("Unexpected databaseName, expected: %s, but found: %s", databaseName, gluePartition.getDatabaseName()));
            }
            if (!tableName.equals(gluePartition.getTableName())) {
                throw new IllegalArgumentException(format("Unexpected tableName, expected: %s, but found: %s", tableName, gluePartition.getTableName()));
            }
            Partition.Builder partitionBuilder = Partition.builder()
                    .setDatabaseName(databaseName)
                    .setTableName(tableName)
                    .setValues(gluePartition.getValues()) // No memoization benefit
                    .setColumns(columnsConverter.apply(sd.getColumns()))
                    .setParameters(parametersConverter.apply(gluePartition.getParameters()));

            storageConverter.setStorageBuilder(sd, partitionBuilder.getStorageBuilder(), tableParameters);

            return partitionBuilder.build();
        }
    }

    private static final class StorageConverter
    {
        private final Function<List<String>, List<String>> bucketColumns = memoizeLast(ImmutableList::copyOf);
        private final Function<List<com.amazonaws.services.glue.model.Order>, List<SortingColumn>> sortColumns = memoizeLast(StorageConverter::createSortingColumns);
        private final UnaryOperator<Optional<HiveBucketProperty>> bucketProperty = memoizeLast();
        private final Function<Map<String, String>, Map<String, String>> serdeParametersConverter = parametersConverter();
        private final StorageFormatConverter storageFormatConverter = new StorageFormatConverter();

        public void setStorageBuilder(StorageDescriptor sd, Storage.Builder storageBuilder, Map<String, String> tableParameters)
        {
            requireNonNull(sd.getSerdeInfo(), "StorageDescriptor SerDeInfo is null");
            SerDeInfo serdeInfo = sd.getSerdeInfo();

            storageBuilder.setStorageFormat(storageFormatConverter.createStorageFormat(serdeInfo, sd))
                    .setLocation(nullToEmpty(sd.getLocation()))
                    .setBucketProperty(convertToBucketProperty(tableParameters, sd))
                    .setSkewed(sd.getSkewedInfo() != null && !isNullOrEmpty(sd.getSkewedInfo().getSkewedColumnNames()))
                    .setSerdeParameters(serdeParametersConverter.apply(serdeInfo.getParameters()))
                    .build();
        }

        private Optional<HiveBucketProperty> convertToBucketProperty(Map<String, String> tableParameters, StorageDescriptor sd)
        {
            if (sd.getNumberOfBuckets() > 0) {
                if (isNullOrEmpty(sd.getBucketColumns())) {
                    throw new TrinoException(HIVE_INVALID_METADATA, "Table/partition metadata has 'numBuckets' set, but 'bucketCols' is not set");
                }
                List<String> bucketColumns = this.bucketColumns.apply(sd.getBucketColumns());
                List<SortingColumn> sortedBy = this.sortColumns.apply(sd.getSortColumns());
                BucketingVersion bucketingVersion = HiveBucketing.getBucketingVersion(tableParameters);
                return bucketProperty.apply(Optional.of(new HiveBucketProperty(bucketColumns, bucketingVersion, sd.getNumberOfBuckets(), sortedBy)));
            }
            return Optional.empty();
        }

        private static List<SortingColumn> createSortingColumns(List<com.amazonaws.services.glue.model.Order> sortColumns)
        {
            if (isNullOrEmpty(sortColumns)) {
                return ImmutableList.of();
            }
            return mappedCopy(sortColumns, column -> new SortingColumn(column.getColumn(), Order.fromMetastoreApiOrder(column.getSortOrder(), "unknown")));
        }
    }

    private static final class StorageFormatConverter
    {
        private static final StorageFormat ALL_NULLS = StorageFormat.createNullable(null, null, null);
        private final UnaryOperator<String> serializationLib = memoizeLast();
        private final UnaryOperator<String> inputFormat = memoizeLast();
        private final UnaryOperator<String> outputFormat = memoizeLast();
        // Second phase to attempt memoization on the entire instance beyond just the fields
        private final UnaryOperator<StorageFormat> storageFormat = memoizeLast();

        public StorageFormat createStorageFormat(SerDeInfo serdeInfo, StorageDescriptor storageDescriptor)
        {
            String serializationLib = this.serializationLib.apply(serdeInfo.getSerializationLibrary());
            String inputFormat = this.inputFormat.apply(storageDescriptor.getInputFormat());
            String outputFormat = this.outputFormat.apply(storageDescriptor.getOutputFormat());
            if (serializationLib == null && inputFormat == null && outputFormat == null) {
                return ALL_NULLS;
            }
            return this.storageFormat.apply(StorageFormat.createNullable(serializationLib, inputFormat, outputFormat));
        }
    }

    public static <T, R> List<R> mappedCopy(List<T> list, Function<T, R> mapper)
    {
        requireNonNull(list, "list is null");
        requireNonNull(mapper, "mapper is null");
        //  Uses a pre-sized builder to avoid intermediate allocations and copies, which is especially significant when the
        //  number of elements is large and the size of the resulting list can be known in advance
        ImmutableList.Builder<R> builder = ImmutableList.builderWithExpectedSize(list.size());
        for (T item : list) {
            builder.add(mapper.apply(item));
        }
        return builder.build();
    }
}
