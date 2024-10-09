package org.querytest;

import org.example.domain.query.QDataWithFormulaMain;

import org.junit.jupiter.api.Test;

public class QDataWithFormulaMainTest {
  @Test
  public void testFilterMany() {
    new QDataWithFormulaMain()
      .metaData.filterMany(metadata -> metadata.id.metaKey.eq(""))
      .findList();
  }
}
