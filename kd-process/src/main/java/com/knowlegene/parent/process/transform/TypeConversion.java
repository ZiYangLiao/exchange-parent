package com.knowlegene.parent.process.transform;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.parser.Feature;
import com.knowlegene.parent.config.util.BaseUtil;
import com.knowlegene.parent.process.common.constantenum.Neo4jEnum;
import com.knowlegene.parent.process.model.DefaultHCatRecord;
import com.knowlegene.parent.process.model.ObjectCoder;
import com.knowlegene.parent.process.model.neo4j.Neo4jObject;
import com.knowlegene.parent.process.util.SqlUtil;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.io.hadoop.WritableCoder;
import org.apache.beam.sdk.schemas.Schema;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.values.Row;
import org.apache.hive.hcatalog.common.HCatException;
import org.apache.hive.hcatalog.data.HCatRecord;
import org.apache.hive.hcatalog.data.schema.HCatSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 类型转换自定义
 * @Author: limeng
 * @Date: 2019/8/27 14:36
 */
public class TypeConversion implements Serializable {
    /**
     * 类型序列化
     * @return
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Coder<HCatRecord> getOutputCoder() {
        return (Coder) WritableCoder.of(DefaultHCatRecord.class);
    }


    public static class RowAndHCatRecord extends DoFn<Row, HCatRecord> {
        private Logger logger = LoggerFactory.getLogger(this.getClass());
        private static final long serialVersionUID = 3987880242259032890L;
        private final Schema type;
        private HCatSchema hCatSchema;
        private List<String> fieldNames;
        private HCatRecord record;

        public RowAndHCatRecord(Schema type) {
            this.type = type;
        }

        @Setup
        public void setUp() throws HCatException {
            logger.info("row to HCatRecord  start");
            hCatSchema = SqlUtil.getRowAndHCatSchema(type);
            fieldNames = type.getFieldNames();
        }



        @ProcessElement
        public void processElement(ProcessContext ctx) throws Exception {
            Row element = ctx.element();
            if(!BaseUtil.isBlankSet(fieldNames)){

                record = new DefaultHCatRecord(fieldNames.size());
                for(String fieldName:fieldNames){
                    Object value = element.getValue(fieldName);
                    if(value == null){
                        value = "";
                    }else if(type.getField(fieldName).getType().getTypeName().isDateType()){
                        value = java.sql.Timestamp.valueOf(value.toString());
                    }
                    record.set(fieldName,hCatSchema,value);
                }
                ctx.output(record);
            }
        }
    }


    public static class HCatRecordAndString extends DoFn<HCatRecord,String> {
        private Logger logger = LoggerFactory.getLogger(this.getClass());
        private static final long serialVersionUID = -7948345114276091219L;
        private final String terminated;
        private StringBuffer sb=null;
        List<Object> all = null;

        @Setup
        public void setup(){
            logger.info("HCatRecord to string start");
        }

        public HCatRecordAndString(String terminated) {
            this.terminated = terminated;
        }

        @ProcessElement
        public void processElement(ProcessContext ctx) throws Exception {
            HCatRecord element = ctx.element();
            all = element.getAll();
            boolean first = true;
            sb = new StringBuffer();
            for(Object o:all){
                if(!first){
                    sb.append(terminated);
                }
                sb.append(o.toString());
                first = false;
            }
            ctx.output(sb.toString());
        }
    }


    public static class RowAndString extends DoFn<Row,String> {
        private static final long serialVersionUID = -6598813617294579482L;
        private Logger logger = LoggerFactory.getLogger(this.getClass());
        private final String terminated;
        private StringBuffer sb=null;
        private List<Object> values=null;

        @Setup
        public void setup(){
            logger.info("row to string start");
        }

        public RowAndString(String terminated) {
            this.terminated = terminated;
        }
        @ProcessElement
        public void processElement(ProcessContext ctx) throws Exception {
            Row element = ctx.element();
            values = element.getValues();
            boolean first = true;
            sb = new StringBuffer();
            for(Object o:values){
                if(!first){
                    sb.append(terminated);
                }
                sb.append(o.toString());
                first = false;
            }
            ctx.output(sb.toString());
        }
    }

    /**
     * cvs转换row
     */
    public static class StringAndRow extends DoFn<String,Row>{
        private static final long serialVersionUID = 368096250333904623L;
        private final Schema type;
        private final String fieldDelim;
        private List<Object> values;
        private  Logger logger = LoggerFactory.getLogger(this.getClass());
        @Setup
        public void setup(){
            logger.info("string to row start");
        }

        public StringAndRow(Schema type,String fieldDelim) {
            this.type = type;
            this.fieldDelim = fieldDelim;
        }

        @ProcessElement
        public void processElement(ProcessContext ctx) throws Exception {
            String element = ctx.element();
            String[] split = element.split(fieldDelim);
            int fieldCount = type.getFieldCount();
            if(fieldCount > 0){
                Row build = Row.withSchema(type).attachValues(Arrays.asList(split)).build();
                ctx.output(build);
            }
        }
    }

    /**
     * json 转换row
     */
    public static class JsonAndRow extends DoFn<String,Row>{
        private static final long serialVersionUID = -6719004422892928263L;

        private  Schema type;
        private HashMap result;
        private  Logger logger = LoggerFactory.getLogger(this.getClass());
        private List<Object> values;

        @Setup
        public void setup(){
            logger.info("json to row start");
        }

        @ProcessElement
        public void processElement(ProcessContext ctx) throws Exception {
            String element = ctx.element();
            //保证嵌套顺序
            result =  JSON.parseObject(element, LinkedHashMap.class, Feature.OrderedField);
            if(type == null){
                Set set = result.keySet();
                Iterator iterator = set.iterator();
                List<Schema.Field> fields =new ArrayList<>();
                while (iterator.hasNext()){
                    String next = iterator.next().toString();
                    fields.add(Schema.Field.of(next, Schema.FieldType.STRING));
                }
                type = Schema.builder().addFields(fields).build();
            }
            if(type != null){
                values=new ArrayList<>();
                for(String name:type.getFieldNames()){
                    values.add(result.get(name));
                }
                Row row = Row.withSchema(type).attachValues(values).build();
                ctx.output(row);
            }else{
                logger.error("schema is null");
            }
        }
    }

    /**
     * row 转换 json
     */
    public static  class  RowAndJson extends DoFn<Row,String>{
        private static final long serialVersionUID = -7088953003682501730L;
        private final Schema type;
        private Logger logger = LoggerFactory.getLogger(this.getClass());
        private Pattern pattern = Pattern.compile("\\[.*\\]");
        @Setup
        public void setup(){
            logger.info("row to json start");
        }
        public RowAndJson(Schema type) {
            this.type = type;
        }

        @ProcessElement
        public void processElement(ProcessContext ctx) throws Exception {
            Row element = ctx.element();
            int fieldCount = element.getFieldCount();
            if(fieldCount > 0 ){
                List<Schema.Field> fields = type.getFields();
                String sql1="{%s}";
                boolean first=false;
                Schema.TypeName typeName = null;
                String name =null;
                StringBuffer sb=new StringBuffer();
                Object value = null;
                boolean numericType = false;
                //boolean dateType =false;
                for(Schema.Field field : fields){
                    if(first){
                       sb.append(",");
                    }
                    first = true;
                    name = field.getName();
                    typeName = field.getType().getTypeName();
                    numericType =typeName.isNumericType();
                    //dateType = typeName.isDateType();

                    value = element.getValue(name);
                    sb.append("\"").append(name).append("\":");
                    if(value == null ){
                        sb.append(value);
                    }else if(isMacth(value.toString())){
                        sb.append(value);
                    }else if(numericType){
                        sb.append(value);
                    }else {
                        sb.append("\"").append(value).append("\"");
                    }
                }

                String sql2=sb.toString();
                if(BaseUtil.isNotBlank(sql2)){
                    String format = String.format(sql1, sql2);
                    ctx.output(format);
                }
            }
        }

        private boolean isMacth(String value){
            boolean result=false;
            if(BaseUtil.isNotBlank(value)){
                Matcher matcher = pattern.matcher(value);
                result= matcher.matches();
            }
            return result;
        }
    }


    public  static class HCatRecordAndRow  extends DoFn<HCatRecord,Row>{
        private Logger logger = LoggerFactory.getLogger(this.getClass());
        private static final long serialVersionUID = -3190891235388132247L;
        private final Schema type;

        public HCatRecordAndRow(Schema type) {
            this.type = type;
        }

        @ProcessElement
        public void processElement(ProcessContext ctx) throws Exception {
            HCatRecord element = ctx.element();
            List<Object> all = element.getAll();
            List<Object> result=new ArrayList<>();
            for (Object o:all){
                if(o== null){
                    o="";
                }
                result.add(o);
            }
            Row build = Row.withSchema(type).attachValues(result).build();
            ctx.output(build);
        }
    }

    /**
     * map转换row
     */
    public  static class MapAndRow  extends DoFn<Map<String, String>,Row>{
        private static final long serialVersionUID = 8363029073596802878L;
        private Logger logger = LoggerFactory.getLogger(this.getClass());
        private final Schema type;
        public MapAndRow(Schema type) {
            logger.info("map to row start");
            this.type = type;
        }
        @ProcessElement
        public void processElement(ProcessContext ctx) throws Exception {
            Map<String, String> element = ctx.element();
            if(!BaseUtil.isBlankMap(element)){
                List<String> fieldNames = type.getFieldNames();
                if(!BaseUtil.isBlankSet(fieldNames)){
                    List<Object> result=new ArrayList<>();
                    for(String name:fieldNames){
                        String value = element.get(name);
                        if(BaseUtil.isBlank(value)){
                            result.add("");
                        }else{
                            result.add(value);
                        }

                    }

                    if(!BaseUtil.isBlankSet(result)){
                        Row row = Row.withSchema(type).attachValues(result).build();
                        ctx.output(row);
                    }
                }
            }
        }
    }


    /**
     * map转换row
     */
    public  static class MapObjectAndRow  extends DoFn<Map<String, ObjectCoder>,Row>{
        private static final long serialVersionUID = 8363029073596802878L;
        private Logger logger = LoggerFactory.getLogger(this.getClass());
        private final Schema type;
        public MapObjectAndRow(Schema type) {
            logger.info("map to row start");
            this.type = type;
        }
        @ProcessElement
        public void processElement(ProcessContext ctx) throws Exception {
            Map<String, ObjectCoder> element = ctx.element();
            if(!BaseUtil.isBlankMap(element)){
                List<String> fieldNames = type.getFieldNames();
                if(!BaseUtil.isBlankSet(fieldNames)){
                    List<Object> result=new ArrayList<>();
                    for(String name:fieldNames){
                        Object value = element.get(name).getValue();
                        if(value == null){
                            result.add("");
                        }else{
                            result.add(value);
                        }

                    }

                    if(!BaseUtil.isBlankSet(result)){
                        Row row = Row.withSchema(type).attachValues(result).build();
                        ctx.output(row);
                    }
                }
            }
        }
    }

    public  static class RowAndNeo4jObject  extends DoFn<Row, Neo4jObject>{
        private Logger logger = LoggerFactory.getLogger(this.getClass());
        private Integer optionsType;
        private final String startId=":START_ID";
        private final String endId=":END_ID";
        private final String id="id:ID";
        private final String type;


        private final List<String> keys;
        private Map<String,Object> parMap;
        private List<Object> values;
        private Neo4jObject neo4jObject;

        @Setup
        public void setup(){
            logger.info("row to neo4jObject start");
        }

        /**
         *
         * @param type  创建连接的标签名称
         * @param optionsType 操作类型
         * @param keys 列名称
         */
        public RowAndNeo4jObject(String type,Integer optionsType, List<String> keys) {
            this.type = type;
            this.optionsType = optionsType;
            this.keys = keys;
        }

        public RowAndNeo4jObject(String type,List<String> keys) {
            this.keys = keys;
            this.type = type;
        }

        @ProcessElement
        public void processElement(ProcessContext ctx) throws Exception {
            Row element = ctx.element();
            values = element.getValues();
            parMap = new HashMap<>();

            getMapValue();

            if(!BaseUtil.isBlankMap(parMap)){
                neo4jObject = new Neo4jObject();
                neo4jObject.setParMap(parMap);
                ctx.output(neo4jObject);
            }
        }


        private void getMapValue() {
            if(optionsType == null){
                if(values.size() == keys.size()){
                    for(int i=0;i < keys.size();i++) {
                        String key = keys.get(i);
                        String value = values.get(i).toString();
                        parMap.put(key,value);
                    }
                }
            }else{
                if(optionsType == Neo4jEnum.RELATE.getValue()){
                    if(values.size() == keys.size()){
                        for(int i=0;i < keys.size();i++){
                            String key= keys.get(i);
                            String value = values.get(i).toString();

                            if(BaseUtil.isNotBlank(type) && key.equalsIgnoreCase(type)){
                                parMap.put(type,value);
                            }else if(key.contains(startId)){
                                parMap.put("startid",value);
                            }else if(key.contains(endId)){
                                parMap.put("endid",value);
                            }else{
                                parMap.put(key,value);
                            }
                        }
                    }

                }else if(optionsType == Neo4jEnum.SAVE.getValue()){
                    if(values.size() == keys.size()){
                        for(int i=0;i < keys.size();i++){
                            String key= keys.get(i);
                            String value = values.get(i).toString();
                            if(key.contains(id)){
                                parMap.put("id", value);
                            }else{
                                parMap.put(key,value);
                            }
                        }
                    }
                }
            }
        }
    }

}
