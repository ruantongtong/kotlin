/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.quickfix.expectactual

import com.intellij.openapi.module.Module
import com.intellij.psi.PsiPackage
import org.jetbrains.kotlin.idea.core.KotlinNameSuggester
import org.jetbrains.kotlin.idea.core.findOrCreateDirectoryForPackage
import org.jetbrains.kotlin.idea.core.getPackage
import org.jetbrains.kotlin.idea.refactoring.createKotlinFile
import org.jetbrains.kotlin.idea.util.application.runWriteAction
import org.jetbrains.kotlin.psi.*

fun createFileForPackage(module: Module, fileName: String, containerPackage: PsiPackage?, packageDirective: KtPackageDirective?): KtFile? {
    val directory = findOrCreateDirectoryForPackage(
        module, containerPackage?.qualifiedName ?: ""
    ) ?: return null
    return runWriteAction {
        val fileNameWithExtension = "$fileName.kt"
        val existingFile = directory.findFile(fileNameWithExtension)
        val packageName =
            if (packageDirective?.packageNameExpression == null) directory.getPackage()?.qualifiedName
            else packageDirective.fqName.asString()
        if (existingFile is KtFile) {
            val existingPackageDirective = existingFile.packageDirective
            if (existingFile.declarations.isNotEmpty() &&
                existingPackageDirective?.fqName != packageDirective?.fqName
            ) {
                val newName = KotlinNameSuggester.suggestNameByName(fileName) {
                    directory.findFile("$it.kt") == null
                } + ".kt"
                createKotlinFile(newName, directory, packageName)
            } else {
                existingFile
            }
        } else {
            createKotlinFile(fileNameWithExtension, directory, packageName)
        }
    }
}

fun KtPsiFactory.createClassCopyByText(originalClass: KtClassOrObject): KtClassOrObject {
    val text = originalClass.text
    return if (originalClass is KtObjectDeclaration) {
        if (originalClass.isCompanion()) {
            createCompanionObject(text)
        } else {
            createObject(text)
        }
    } else {
        createClass(text)
    }
}