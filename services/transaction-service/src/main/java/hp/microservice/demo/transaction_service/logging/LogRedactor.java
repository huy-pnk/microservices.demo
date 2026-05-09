package hp.microservice.demo.transaction_service.logging;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Set;

@Component
public class LogRedactor {

    private final Set<String> sensitiveFields;

    public LogRedactor(@Value("${logging.redaction.fields:pan,cvv,cvc}") String fields) {
        this.sensitiveFields = Set.copyOf(Arrays.asList(fields.split(",")));
    }

    public Object sanitise(Object dto) {
        if (dto == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(dto.getClass().getSimpleName()).append("{");
        Field[] fields = dto.getClass().getDeclaredFields();
        for (int i = 0; i < fields.length; i++) {
            Field field = fields[i];
            field.setAccessible(true);
            sb.append(field.getName()).append("=");
            if (sensitiveFields.contains(field.getName().toLowerCase())) {
                sb.append("***REDACTED***");
            } else {
                try {
                    sb.append(field.get(dto));
                } catch (IllegalAccessException e) {
                    sb.append("???");
                }
            }
            if (i < fields.length - 1) {
                sb.append(", ");
            }
        }
        sb.append("}");
        return sb.toString();
    }
}
