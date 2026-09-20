package com.yx.uavfire.common.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.*;
import java.util.regex.*;

/** Human names are canonical; legacy codes are accepted only at compatibility boundaries. */
public final class DeviceModelNames {
    private static final Map<String,String> NAMES=new LinkedHashMap<>(), CODES=new LinkedHashMap<>();
    private static final List<String> OFFICIAL=new ArrayList<>();
    private static final Pattern TOKENS;
    static {
        List<String> aliases=new ArrayList<>();
        try(InputStream input=DeviceModelNames.class.getResourceAsStream("/device-models.json")) {
            for(JsonNode row:new ObjectMapper().readTree(input).get("models")) {
                String name=row.get("name").asText(),code=row.get("code").asText();OFFICIAL.add(name);
                List<String> values=new ArrayList<>(List.of(code,name));row.get("aliases").forEach(v->values.add(v.asText()));
                for(String value:values){NAMES.put(normalize(value),name);CODES.put(normalize(value),code);if(!value.equals(name)&&!value.matches("^\\d+-.*")&&!Set.of("L1","L2","P1","DOCK").contains(value.toUpperCase(Locale.ROOT)))aliases.add(value);}
            }
        } catch(Exception e){throw new ExceptionInInitializerError("Device name catalog could not be loaded");}
        aliases.sort(Comparator.comparingInt(String::length).reversed());
        TOKENS=Pattern.compile("(?<![A-Za-z0-9_])(?:"+aliases.stream().map(Pattern::quote).distinct().collect(java.util.stream.Collectors.joining("|"))+")(?![A-Za-z0-9_])",Pattern.CASE_INSENSITIVE);
    }
    private DeviceModelNames(){}
    private static String normalize(String value){return value==null?"":value.trim().toUpperCase(Locale.ROOT).replaceAll("[\\s_-]+","");}
    public static String code(String value){return CODES.getOrDefault(normalize(value),value);}
    public static String full(String value){return value==null?null:NAMES.getOrDefault(normalize(value),text(value));}
    public static String text(String value){
        if(value==null)return null;
        String result=value;
        for(int i=0;i<OFFICIAL.size();i++)result=Pattern.compile(Pattern.quote(OFFICIAL.get(i)),Pattern.CASE_INSENSITIVE).matcher(result).replaceAll(Matcher.quoteReplacement("\u0001"+i+"\u0002"));
        Matcher m=TOKENS.matcher(result);StringBuffer out=new StringBuffer();
        while(m.find())m.appendReplacement(out,Matcher.quoteReplacement(NAMES.get(normalize(m.group()))));m.appendTail(out);
        result=out.toString();for(int i=0;i<OFFICIAL.size();i++)result=result.replace("\u0001"+i+"\u0002",OFFICIAL.get(i));return result;
    }
}
