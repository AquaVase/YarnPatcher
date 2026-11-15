package com.github.yarnpatcher;

/** Both modern SRG and the legacy 1.16-era member/constructor parameter dialect. */
final class SrgNames {
    static boolean method(String name) {
        return name.matches("m_[0-9]+_|func_[0-9]+_[A-Za-z]+_?");
    }
    static boolean field(String name) {
        return name.matches("f_[0-9]+_|field_[0-9]+_[A-Za-z]+_?");
    }
    static boolean parameter(String name) {
        return name.matches("p_(?:i)?[0-9]+_(?:[0-9]+_)?");
    }
    static boolean member(String name) {
        return method(name) || field(name) || parameter(name);
    }
}
