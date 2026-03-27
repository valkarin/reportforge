module com.buraktok.reportforge {
    requires javafx.controls;
    requires javafx.swing;
    requires org.kordamp.ikonli.core;
    requires org.kordamp.ikonli.fontawesome5;
    requires org.kordamp.ikonli.javafx;
    requires java.desktop;
    requires java.prefs;
    requires java.sql;
    requires com.fasterxml.jackson.annotation;
    requires tools.jackson.core;
    requires tools.jackson.databind;
    requires com.luciad.imageio.webp;
    requires io.pebbletemplates;
    requires minify.html;
    requires org.slf4j;
    requires org.apache.logging.log4j;
    requires org.apache.logging.log4j.core;
    requires org.apache.logging.log4j.slf4j2.impl;
    requires org.xerial.sqlitejdbc;

    exports com.buraktok.reportforge;
    opens com.buraktok.reportforge.persistence to tools.jackson.databind;
}
