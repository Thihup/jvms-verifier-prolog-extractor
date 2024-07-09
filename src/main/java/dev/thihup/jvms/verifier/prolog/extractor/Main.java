package dev.thihup.jvms.verifier.prolog.extractor;

import dev.nipafx.args.Args;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.StructuredTaskScope;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Gatherer;
import java.util.stream.IntStream;

import static java.util.concurrent.StructuredTaskScope.Subtask.State.SUCCESS;

public class Main {

    record Result(int version, String spec){}

    public record Arguments(Optional<Integer> startVersion, Optional<Integer> endVersion, Optional<String> url, Optional<String> outputFolder, Optional<Boolean> keepDuplicates) {}

    public static void main(String[] args) throws Throwable {

        Arguments parse = Args.parse(args, Arguments.class);
        try(var scope = new StructuredTaskScope<Result>()) {

            // Fetch the HTML content from the URL
            String url = parse.url().orElse("https://docs.oracle.com/javase/specs/jvms/se%s/html/jvms-4.html#jvms-4.10");
            List<StructuredTaskScope.Subtask<Result>> list = IntStream.rangeClosed(parse.startVersion().orElse(7), parse.endVersion().orElse(Runtime.version().feature()))
                    .mapToObj(args1 -> Map.entry(args1, url.formatted(args1)))
                    .map(map -> scope.fork(() -> new Result(map.getKey(), extracted(map.getValue())))).toList();

            scope.join();

            boolean b = parse.keepDuplicates().orElse(false);
            Map<Integer, String> collect = list.stream()
                .filter(x -> x.state() == SUCCESS)
                .map(StructuredTaskScope.Subtask::get)
                .gather(deduplicateAdjacent(Result::spec, b))
                .collect(Collectors.toMap(Result::version, Result::spec));

            for (Map.Entry<Integer, String> integerStringEntry : collect.entrySet()) {
                Files.writeString(Paths.get(parse.outputFolder().orElse("."), "jvms-%s-prolog.pl".formatted(integerStringEntry.getKey())), integerStringEntry.getValue());
            }
        }

    }

    public static <T, R> Gatherer<T,?,T> deduplicateAdjacent(Function<T, R> mapper, boolean skip) {
        class State { R prev; boolean hasPrev; }
        return Gatherer.ofSequential(
                State::new,
                (state, element, downstream) -> {
                    if (skip) {
                        return downstream.push(element);
                    }
                    R apply = mapper.apply(element);
                    if (!state.hasPrev) {
                        state.hasPrev = true;
                        state.prev = apply;
                        return downstream.push(element);
                    } else if (!Objects.equals(state.prev, apply)) {
                        state.prev = apply;
                        return downstream.push(element);
                    } else {
                        return true; // skip duplicate
                    }
                }
        );
    }

    private static String extracted(String url) {
        Document doc = null;
        try {
            doc = Jsoup.connect(url).get();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        // Parse the HTML and find the div element containing the a tag with name='jvms-4.10'
        Element jvm410Node = doc.selectFirst("div.section:has(a[name=jvms-4.10])");

        // If the div is found, select all <pre class="programlisting"> elements inside it
        if (jvm410Node != null) {
            Elements preElements = jvm410Node.select("pre.programlisting");
            // Print the outer HTML of the found elements
            int size = preElements.size();
            String prolog = preElements.stream()
                    .limit(size - 3)
                    .map(Element::text)
                    .map(Object::toString)
                    .filter(x -> !x.contains("Verification type hierarchy:"))
                    .map(x -> Parser.unescapeEntities(x, false))
                    .map(Main::fixProlog)
                    .collect(Collectors.joining("\n"));

            return prolog;

        }
        throw new RuntimeException("Could not parse");
    }

    private static String fixProlog(String s) {

        // 4.10.1.8. Type Checking for protected Members
        // Current:
        //
        // classesInOtherPkgWithProtectedMember(Class, MemberName,
        //                                     MemberDescriptor, MemberClassName,
        //                                     [class(MemberClassName, L) | Tail],
        //                                     T] :-
        // Correction:
        // classesInOtherPkgWithProtectedMember(Class, MemberName,
        //                                     MemberDescriptor, MemberClassName,
        //                                     [class(MemberClassName, L) | Tail],
        //                                     T) :-
        if (s.contains("classesInOtherPkgWithProtectedMember")) {
            return s.replace("T] :-", "T) :-");
        }

        // 4.10.1.9. Type Checking Instructions: ldc, ldc_w, ldc2_w
        // Current:
        // instructionHasEquivalentTypeRule(ldc_w(CP), ldc(CP))
        // Correction:
        // instructionHasEquivalentTypeRule(ldc_w(CP), ldc(CP)).

        if (s.startsWith("instructionHasEquivalentTypeRule") && !s.endsWith(".")) {
            return s + ".";
        }

        // 4.10.1.6. Type Checking Methods with Code
        // Current:
        // isInitHandler(Environment, Handler) :-
        //    Environment = environment(_Class, Method, _, Instructions, _, _),
        //    isInit(Method).
        //    member(instruction(_, invokespecial(CP)), Instructions),
        //    CP = method(MethodClassName, '<init>', Descriptor).

        // Correction:
        // isInitHandler(Environment, Handler) :-
        //    Environment = environment(_Class, Method, _, Instructions, _, _),
        //    isInit(Method),
        //    member(instruction(_, invokespecial(CP)), Instructions),
        //    CP = method(MethodClassName, '<init>', Descriptor).
        if (s.equals("isInit(Method).")) {
            return "isInit(Method),";
        }

        // 4.10.1.9. Type Checking Instructions: baload
        // Current:
        // instructionIsTypeSafe(baload, Environment, _Offset, StackFrame,
        //                      NextStackFrame, ExceptionStackFrame) :
        //    nth1OperandStackIs(2, StackFrame, ArrayType),
        //    isSmallArray(ArrayType),
        //    validTypeTransition(Environment, [int, top], int,
        //                        StackFrame, NextStackFrame),
        //    exceptionStackFrame(StackFrame, ExceptionStackFrame).
        // Correction:
        // instructionIsTypeSafe(baload, Environment, _Offset, StackFrame,
        //                      NextStackFrame, ExceptionStackFrame) :-
        //    nth1OperandStackIs(2, StackFrame, ArrayType),
        //    isSmallArray(ArrayType),
        //    validTypeTransition(Environment, [int, top], int,
        //                        StackFrame, NextStackFrame),
        //    exceptionStackFrame(StackFrame, ExceptionStackFrame).
        if (s.endsWith("NextStackFrame, ExceptionStackFrame) :")) {
            return s + "-";
        }

        return s;
    }
}
