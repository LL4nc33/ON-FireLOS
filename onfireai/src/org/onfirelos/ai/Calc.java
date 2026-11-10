/*
 * Copyright (C) 2026 ON-FireLOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.onfirelos.ai;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Locale;

/**
 * Arithmetic worked out exactly, here on the tablet: a language model miscounts with long
 * numbers. Decimals to 34 digits; the four rules, powers, percent the way a calculator takes
 * it (100 + 19% = 119), roots, factorials, and a few functions (angles in degrees).
 */
final class Calc {

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** The calculator app, for showing a calculation there. */
    static final String CALCULATOR = "com.android.calculator2";
    private static final String ACTION_CALCULATE = "org.onfirelos.calculator.action.CALCULATE";

    private final String mText;
    private int mPos;
    /** The last term read was a bare percentage ("19%"), to be taken of what is before it. */
    private boolean mPercent;

    private Calc(String text) {
        mText = text;
    }

    /** The value, or an IllegalArgumentException that says what is wrong. */
    static BigDecimal evaluate(String expression) {
        final Calc c = new Calc(normal(expression));
        if (c.mText.isEmpty()) {
            throw new IllegalArgumentException("empty expression");
        }
        final BigDecimal v = c.sum();
        if (c.mPos < c.mText.length()) {
            throw new IllegalArgumentException("cannot read from \""
                    + c.mText.substring(c.mPos) + "\"");
        }
        return v;
    }

    /** One way of writing: points for decimals, * and /, no spaces, the words in lower case. */
    static String normal(String expression) {
        String t = expression.toLowerCase(Locale.ROOT).trim()
                .replace('×', '*').replace('·', '*').replace('⋅', '*')
                .replace('÷', '/').replace(':', '/').replace('−', '-')
                .replace('–', '-').replace('√', 'r').replace('π', 'p')
                .replaceAll("\\s+", "");
        t = t.replace("sqrt", "r").replace("wurzel", "r").replace("pi", "p");
        // A comma between digits is a decimal one here ("3,5"); thousands come without.
        t = t.replaceAll("(\\d),(\\d)", "$1.$2");
        return t;
    }

    // ---------------------------------------------------------------- reading

    private BigDecimal sum() {
        BigDecimal v = product();
        while (mPos < mText.length()) {
            final char op = mText.charAt(mPos);
            if (op != '+' && op != '-') {
                break;
            }
            mPos++;
            BigDecimal right = product();
            // "100 + 19%": nineteen percent of the hundred, as on a calculator.
            if (mPercent) {
                right = v.multiply(right, MC);
            }
            v = op == '+' ? v.add(right, MC) : v.subtract(right, MC);
        }
        mPercent = false;
        return v;
    }

    private BigDecimal product() {
        BigDecimal v = unary();
        boolean percent = mPercent;
        while (mPos < mText.length()) {
            final char op = mText.charAt(mPos);
            if (op != '*' && op != '/') {
                break;
            }
            mPos++;
            final BigDecimal right = unary();
            if (op == '*') {
                v = v.multiply(right, MC);
            } else {
                if (right.signum() == 0) {
                    throw new IllegalArgumentException("division by zero");
                }
                v = v.divide(right, MC);
            }
            percent = false;
        }
        mPercent = percent;
        return v;
    }

    /** The power binds closer than a sign before it: -3^2 is -9, 2^-1 is 0.5. */
    private BigDecimal power() {
        final BigDecimal base = postfix();
        if (mPos < mText.length() && mText.charAt(mPos) == '^') {
            mPos++;
            final BigDecimal exponent = unary();
            mPercent = false;
            return pow(base, exponent);
        }
        return base;
    }

    private BigDecimal unary() {
        if (mPos < mText.length() && (mText.charAt(mPos) == '-' || mText.charAt(mPos) == '+')) {
            final boolean minus = mText.charAt(mPos++) == '-';
            final BigDecimal v = unary();
            return minus ? v.negate() : v;
        }
        return power();
    }

    private BigDecimal postfix() {
        BigDecimal v = primary();
        mPercent = false;
        while (mPos < mText.length()) {
            final char c = mText.charAt(mPos);
            if (c == '%') {
                mPos++;
                v = v.divide(HUNDRED, MC);
                mPercent = true;
            } else if (c == '!') {
                mPos++;
                v = factorial(v);
                mPercent = false;
            } else {
                break;
            }
        }
        return v;
    }

    private BigDecimal primary() {
        if (mPos >= mText.length()) {
            throw new IllegalArgumentException("expression ends too early");
        }
        final char c = mText.charAt(mPos);
        if (c == '(') {
            mPos++;
            final BigDecimal v = sum();
            expect(')');
            return v;
        }
        if (Character.isDigit(c) || c == '.') {
            final int start = mPos;
            while (mPos < mText.length()
                    && (Character.isDigit(mText.charAt(mPos)) || mText.charAt(mPos) == '.')) {
                mPos++;
            }
            // 1e6, 2.5e-3
            if (mPos + 1 < mText.length() && mText.charAt(mPos) == 'e'
                    && (Character.isDigit(mText.charAt(mPos + 1))
                    || (mText.charAt(mPos + 1) == '-' && mPos + 2 < mText.length()
                    && Character.isDigit(mText.charAt(mPos + 2))))) {
                mPos += 2;
                while (mPos < mText.length() && Character.isDigit(mText.charAt(mPos))) {
                    mPos++;
                }
            }
            try {
                return new BigDecimal(mText.substring(start, mPos));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("not a number: "
                        + mText.substring(start, mPos));
            }
        }
        if (c == 'p') {
            mPos++;
            return new BigDecimal(Math.PI, MC);
        }
        if (c == 'r') {
            mPos++;
            return sqrt(argument());
        }
        if (Character.isLetter(c)) {
            final int start = mPos;
            while (mPos < mText.length() && Character.isLetter(mText.charAt(mPos))) {
                mPos++;
            }
            final String name = mText.substring(start, mPos);
            if ("e".equals(name)) {
                return new BigDecimal(Math.E, MC);
            }
            final double x = argument().doubleValue();
            switch (name) {
                case "sin": return exactish(Math.sin(Math.toRadians(x)));
                case "cos": return exactish(Math.cos(Math.toRadians(x)));
                case "tan": return exactish(Math.tan(Math.toRadians(x)));
                case "ln": return exactish(Math.log(positive(x)));
                case "log": return exactish(Math.log10(positive(x)));
                case "abs": return BigDecimal.valueOf(Math.abs(x));
                default: throw new IllegalArgumentException("unknown function " + name);
            }
        }
        throw new IllegalArgumentException("cannot read from \"" + mText.substring(mPos) + "\"");
    }

    /** "(…)" after a function, or a plain number or constant ("√2"). */
    private BigDecimal argument() {
        if (mPos < mText.length() && mText.charAt(mPos) == '(') {
            mPos++;
            final BigDecimal v = sum();
            expect(')');
            return v;
        }
        return postfix();
    }

    private void expect(char c) {
        if (mPos >= mText.length() || mText.charAt(mPos) != c) {
            throw new IllegalArgumentException("missing " + c);
        }
        mPos++;
    }

    // ---------------------------------------------------------------- arithmetic

    private static BigDecimal pow(BigDecimal base, BigDecimal exponent) {
        if (exponent.signum() == 0 || exponent.stripTrailingZeros().scale() <= 0) {
            final int n;
            try {
                n = exponent.intValueExact();
            } catch (ArithmeticException e) {
                throw new IllegalArgumentException("exponent too large");
            }
            if (Math.abs(n) > 9999) {
                throw new IllegalArgumentException("exponent too large");
            }
            if (n < 0 && base.signum() == 0) {
                throw new IllegalArgumentException("division by zero");
            }
            return base.pow(n, MC);
        }
        final double r = Math.pow(base.doubleValue(), exponent.doubleValue());
        if (Double.isNaN(r) || Double.isInfinite(r)) {
            throw new IllegalArgumentException("no real result");
        }
        return exactish(r);
    }

    private static BigDecimal sqrt(BigDecimal x) {
        if (x.signum() < 0) {
            throw new IllegalArgumentException("root of a negative number");
        }
        if (x.signum() == 0) {
            return BigDecimal.ZERO;
        }
        // Newton from the double's guess: a few rounds reach all 34 digits.
        BigDecimal r = new BigDecimal(Math.sqrt(x.doubleValue()), MC);
        if (r.signum() == 0) {
            r = BigDecimal.ONE;
        }
        final BigDecimal two = BigDecimal.valueOf(2);
        for (int i = 0; i < 8; i++) {
            r = r.add(x.divide(r, MC), MC).divide(two, MC);
        }
        return r;
    }

    private static BigDecimal factorial(BigDecimal v) {
        final int n;
        try {
            n = v.stripTrailingZeros().intValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("factorial only of whole numbers");
        }
        if (n < 0 || n > 1000) {
            throw new IllegalArgumentException("factorial only from 0 to 1000");
        }
        BigInteger f = BigInteger.ONE;
        for (int i = 2; i <= n; i++) {
            f = f.multiply(BigInteger.valueOf(i));
        }
        return new BigDecimal(f);
    }

    private static double positive(double x) {
        if (x <= 0) {
            throw new IllegalArgumentException("logarithm of a number not above 0");
        }
        return x;
    }

    /** From a double: rounded to the digits it really has, so sin(30) is 0.5, not 0.4999…. */
    private static BigDecimal exactish(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            throw new IllegalArgumentException("no real result");
        }
        // cos(90) is 0, not 6.1e-17 left over from the double.
        if (Math.abs(d) < 1e-12) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(d).round(new MathContext(14));
    }

    // ---------------------------------------------------------------- writing

    /**
     * For the model: plain, a point for decimals. A whole number exactly as it is (2^64 with
     * all its digits), a fraction to 15 significant digits.
     */
    static String plain(BigDecimal v) {
        final BigDecimal whole = v.stripTrailingZeros();
        if (whole.scale() <= 0 && whole.precision() <= 34) {
            return whole.toPlainString();
        }
        final BigDecimal r = v.round(new MathContext(15)).stripTrailingZeros();
        final int digits = r.precision() - r.scale();
        if (r.signum() != 0 && (digits > 21 || digits < -8)) {
            return r.toString().replace("E+", "e").replace("E", "e");
        }
        return r.toPlainString();
    }

    /** For the card: in the user's way of writing numbers (1.234,5 in German). */
    static String shown(BigDecimal v) {
        final BigDecimal whole = v.stripTrailingZeros();
        if (whole.scale() <= 0 && whole.precision() <= 34) {
            final NumberFormat f = NumberFormat.getIntegerInstance();
            return f.format(whole.toBigIntegerExact());
        }
        final BigDecimal r = v.round(new MathContext(15)).stripTrailingZeros();
        final int digits = r.precision() - r.scale();
        if (r.signum() != 0 && (digits > 16 || digits < -8)) {
            return plain(v).replace('.', decimalPoint());
        }
        final NumberFormat f = NumberFormat.getNumberInstance();
        f.setMaximumFractionDigits(Math.max(0, r.scale()));
        f.setRoundingMode(RoundingMode.HALF_UP);
        return f.format(r);
    }

    /** The expression as it is written by hand: × and ÷, the user's decimal point. */
    static String pretty(String expression) {
        final String t = normal(expression);
        final StringBuilder b = new StringBuilder();
        final char point = decimalPoint();
        for (int i = 0; i < t.length(); i++) {
            final char c = t.charAt(i);
            switch (c) {
                case '*': b.append(" × "); break;
                case '/': b.append(" ÷ "); break;
                case '+': b.append(" + "); break;
                case '-':
                    // A minus sign after an operator or at the start belongs to the number.
                    b.append(i == 0 || "*/+-(^".indexOf(t.charAt(i - 1)) >= 0 ? "−"
                            : " − ");
                    break;
                case '.': b.append(point); break;
                case 'r': b.append('√'); break;
                case 'p': b.append('π'); break;
                default: b.append(c);
            }
        }
        return b.toString().replace("  ", " ").trim();
    }

    private static char decimalPoint() {
        return java.text.DecimalFormatSymbols.getInstance().getDecimalSeparator();
    }

    /** Shows it in the calculator app, typed in and worked out; false without the app. */
    static boolean show(Context context, String expression) {
        // The calculator reads its own names: sqrt( and pi, with a point for decimals.
        final String forApp = normal(expression).replace("r(", "sqrt(").replace("p", "π")
                .replaceAll("r(\\d+(\\.\\d+)?)", "sqrt($1)");
        try {
            context.startActivity(new Intent(ACTION_CALCULATE).setPackage(CALCULATOR)
                    .putExtra("expression", forApp)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (ActivityNotFoundException e) {
            return false;
        }
    }
}
