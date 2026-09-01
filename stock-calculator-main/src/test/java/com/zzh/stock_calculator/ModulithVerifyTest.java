package com.zzh.stock_calculator;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * 领域模块边界校验（Spring Modulith）：
 * verify() 强制——跨模块只能访问对方「基包 API」；
 * 模块内部子包（entity/repository/impl 等）对外不可见，违规直接测试失败。
 */
class ModulithVerifyTest {

	ApplicationModules modules = ApplicationModules.of(StockCalculatorApplication.class);

	@Test
	void verifyModuleBoundaries() {
		modules.forEach(System.out::println);
		modules.verify();
	}

	@Test
	void documentModules() {
		new Documenter(modules).writeDocumentation();
	}
}
