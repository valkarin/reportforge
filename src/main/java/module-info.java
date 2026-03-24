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
    requires com.fasterxml.jackson.core;
    requires com.fasterxml.jackson.databind;
    requires com.luciad.imageio.webp;
    requires io.pebbletemplates;
    requires minify.html;
    requires org.slf4j;
    requires org.slf4j.nop;
    requires org.xerial.sqlitejdbc;

    opens com.buraktok.reportforge.persistence to com.fasterxml.jackson.databind;
    exports com.buraktok.reportforge;
}
