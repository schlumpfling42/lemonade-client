package net.rka.ai.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/**
 * Date and time tools. Models don't know the current date, so give it to them.
 *
 * <h2>How a tool class works (this is the simplest example)</h2>
 * Every public method with {@code @Tool} becomes a tool the model can call:
 * <ul>
 *   <li>the <b>method name</b> is the tool name ({@code currentDateTime});</li>
 *   <li>the {@code @Tool} text is the description the model reads to decide when to call it;</li>
 *   <li>each parameter becomes a JSON-schema property, described by its {@code @P} text;</li>
 *   <li>the return value is converted to text and sent back to the model as the tool result.</li>
 * </ul>
 * LangChain4j builds the schema ({@code ToolSpecifications}), and {@link ToolRegistry} invokes the
 * method when the model asks for it. Invalid input (e.g. a bad date) throws, and the exception
 * message goes back to the model as an error result, so it can correct itself.
 *
 * <h2>Not implemented yet</h2>
 * <ul>
 *   <li>Time zones: "what time is it in Tokyo?" ({@code ZoneId.of("Asia/Tokyo")}).</li>
 *   <li>Date arithmetic: add N days/weeks/months to a date, business days, week numbers.</li>
 *   <li>Parsing natural formats ("next Friday") — the model can usually turn those into ISO dates itself.</li>
 * </ul>
 */
public class DateTimeTools {

    @Tool("Returns the current local date and time")
    public String currentDateTime() {
        // RFC 1123 includes the weekday and time zone offset, e.g. "Sat, 17 Oct 2026 20:15:00 -0700"
        return ZonedDateTime.now().format(DateTimeFormatter.RFC_1123_DATE_TIME);
    }

    @Tool("Returns the day of the week for a date")
    public String dayOfWeek(@P("date in ISO format, e.g. 2026-12-25") String date) {
        return LocalDate.parse(date).getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
    }

    @Tool("Returns the number of days from one date to another (negative if the second date is earlier)")
    public long daysBetween(@P("start date in ISO format, e.g. 2026-01-31") String from,
                            @P("end date in ISO format") String to) {
        return ChronoUnit.DAYS.between(LocalDate.parse(from), LocalDate.parse(to));
    }
}
