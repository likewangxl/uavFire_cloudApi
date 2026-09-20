package com.yx.uavfire.common.model;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import java.io.IOException;
public class DeviceModelNameSerializer extends JsonSerializer<String> {
    @Override public void serialize(String value,JsonGenerator generator,SerializerProvider provider)throws IOException{generator.writeString(DeviceModelNames.full(value));}
}
