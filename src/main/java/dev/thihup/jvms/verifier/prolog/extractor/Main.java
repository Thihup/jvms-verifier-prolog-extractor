package dev.thihup.jvms.verifier.prolog.extractor;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.jsoup.select.Elements;

import java.util.stream.Collectors;

public class Main {

    public static void main(String[] args) throws Throwable {
        // Fetch the HTML content from the URL
        String url = "https://docs.oracle.com/javase/specs/jvms/se17/html/jvms-4.html#jvms-4.10";
        Document doc = Jsoup.connect(url).get();

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

            System.out.println(prolog);

        } else {
            System.out.println("Element not found.");
        }
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
