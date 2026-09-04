/**
 * Shared kernel — cross-cutting types every module needs and none of them owns.
 *
 * <p>Declared as an {@link org.springframework.modulith.ApplicationModule.Type#OPEN OPEN} module so
 * every other module may depend on it without producing a boundary violation. Without it, error
 * codes and session identity would either be duplicated seven times or borrowed through
 * module-to-module dependencies that {@code ApplicationModules.verify()} rejects (ADR-021).
 *
 * <p><strong>What may never live here:</strong> entities, repositories, tables, business rules, or
 * any DTO shared between exactly two modules. The test: if adding something here means two modules
 * must be redeployed together for a business change, it does not belong here.
 */
@org.springframework.modulith.ApplicationModule(
        type = org.springframework.modulith.ApplicationModule.Type.OPEN,
        displayName = "Shared Kernel")
package com.flashseats.shared;
