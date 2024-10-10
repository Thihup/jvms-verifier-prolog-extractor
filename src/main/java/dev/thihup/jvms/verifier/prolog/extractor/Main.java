package dev.thihup.jvms.verifier.prolog.extractor;

import dev.nipafx.args.Args;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.jsoup.select.Elements;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Gatherers;
import java.util.stream.IntStream;

public class Main {

    record Result(int version, @Nullable String spec) {
    }

    public record Arguments(Optional<Integer> startVersion, Optional<Integer> endVersion, Optional<String> url,
                            Optional<String> outputFolder, Optional<Boolean> keepDuplicates, Optional<Boolean> fixProlog) {
    }

    public static void main(String[] args) throws Throwable {

        Arguments parse = Args.parse(args, Arguments.class);
        boolean keepDuplicates = parse.keepDuplicates().orElse(false);
        boolean fixProlog = parse.fixProlog.orElse(true);

        int start = parse.startVersion().orElse(7);
        int end = parse.endVersion().orElse(Runtime.version().feature());

        // Fetch the HTML content from the URL
        String url = parse.url().orElse("https://docs.oracle.com/javase/specs/jvms/se%s/html/jvms-4.html#jvms-4.10");
        Map<Integer, String> collect = IntStream.rangeClosed(start, end)
                .mapToObj(args1 -> Map.entry(args1, url.formatted(args1)))
                .gather(Gatherers.mapConcurrent(end - start, map -> new Result(map.getKey(), extracted(map.getValue(), fixProlog))))
                .filter(x -> x.spec() != null)
                .filter(distinctBy(Result::spec, keepDuplicates))
                .collect(Collectors.toMap(Result::version, Result::spec));

        for (Map.Entry<Integer, String> integerStringEntry : collect.entrySet()) {
            Files.writeString(Paths.get(parse.outputFolder().orElse("."), "jvms-%s-prolog.pl".formatted(integerStringEntry.getKey())), integerStringEntry.getValue());
        }

    }

    private static <T, R> Predicate<R> distinctBy(Function<R, T> a, boolean keepDuplicates) {
        Set<T> objects = Collections.newSetFromMap(new ConcurrentHashMap<>());
        return e -> keepDuplicates || objects.add(a.apply(e));
    }

    @Nullable
    private static String extracted(String url, boolean fixProlog) {
        Document doc;
        try {
            doc = Jsoup.connect(url).get();
        } catch (IOException e) {
            System.err.println("Could not fetch " + url);
            return null;
        }

        // Parse the HTML and find the div element containing the a tag with name='jvms-4.10'
        Element jvm410Node = doc.selectFirst("div.section:has(a[name=jvms-4.10])");

        // If the div is found, select all <pre class="programlisting"> elements inside it
        if (jvm410Node == null) {
            System.err.println("Could not parse the return from " + url);
            return null;
        }
        Elements preElements = jvm410Node.select("pre.programlisting");
        // Print the outer HTML of the found elements
        int size = preElements.size();

        return preElements.stream()
                .limit(size - 3)
                .map(Element::text)
                .map(Object::toString)
                .filter(x -> !x.contains("Verification type hierarchy:"))
                .map(x -> Parser.unescapeEntities(x, false))
                .map((String s) -> fixProlog(s, fixProlog))
                .collect(Collectors.joining("\n"));

    }

    private static String fixProlog(String s, boolean fixProlog) {
        if (!fixProlog) {
            return s;
        }
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
