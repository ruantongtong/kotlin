/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.quickfix.expectactual

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.ide.util.EditorHelper
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaDirectoryService
import com.intellij.psi.codeStyle.CodeStyleManager
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.diagnostics.Diagnostic
import org.jetbrains.kotlin.diagnostics.DiagnosticFactory
import org.jetbrains.kotlin.diagnostics.Errors
import org.jetbrains.kotlin.idea.caches.project.implementedModules
import org.jetbrains.kotlin.idea.caches.resolve.analyzeWithContent
import org.jetbrains.kotlin.idea.core.ShortenReferences
import org.jetbrains.kotlin.idea.core.overrideImplement.*
import org.jetbrains.kotlin.idea.core.toDescriptor
import org.jetbrains.kotlin.idea.quickfix.KotlinIntentionActionsFactory
import org.jetbrains.kotlin.idea.quickfix.KotlinQuickFixAction
import org.jetbrains.kotlin.idea.util.application.executeWriteCommand
import org.jetbrains.kotlin.idea.util.liftToExpected
import org.jetbrains.kotlin.idea.util.module
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.hasActualModifier
import org.jetbrains.kotlin.resolve.BindingContext

sealed class CreateExpectedFix<out D : KtNamedDeclaration>(
    declaration: D,
    private val commonModule: Module,
    private val generateIt: KtPsiFactory.(Project, D) -> D?
) : KotlinQuickFixAction<D>(declaration) {

    override fun getFamilyName() = text

    protected abstract val elementType: String

    override fun getText() = "Create expected $elementType in common module ${commonModule.name}"

    override fun startInWriteAction() = false

    final override fun invoke(project: Project, editor: Editor?, file: KtFile) {
        val element = element ?: return
        val factory = KtPsiFactory(project)

        val expectedFile = getOrCreateImplementationFile() ?: return
        DumbService.getInstance(project).runWhenSmart {
            val generated = factory.generateIt(project, element) ?: return@runWhenSmart

            project.executeWriteCommand("Create expected declaration") {
                if (expectedFile.packageDirective?.fqName != file.packageDirective?.fqName &&
                    expectedFile.declarations.isEmpty()
                ) {
                    val packageDirective = file.packageDirective
                    packageDirective?.let {
                        val oldPackageDirective = expectedFile.packageDirective
                        val newPackageDirective = factory.createPackageDirective(it.fqName)
                        if (oldPackageDirective != null) {
                            oldPackageDirective.replace(newPackageDirective)
                        } else {
                            expectedFile.add(newPackageDirective)
                        }
                    }
                }
                val expectedDeclaration = expectedFile.add(generated) as KtElement
                val reformatted = CodeStyleManager.getInstance(project).reformat(expectedDeclaration)
                val shortened = ShortenReferences.DEFAULT.process(reformatted as KtElement)
                EditorHelper.openInEditor(shortened)?.caretModel?.moveToOffset(shortened.textRange.startOffset)
            }
        }
    }

    private fun getOrCreateImplementationFile(): KtFile? {
        val declaration = element as? KtNamedDeclaration ?: return null
        val parent = declaration.parent
        if (parent is KtFile) {
            for (otherDeclaration in parent.declarations) {
                if (otherDeclaration === declaration) continue
                if (!otherDeclaration.hasActualModifier()) continue
                val expectedDeclaration = otherDeclaration.liftToExpected() ?: continue
                return expectedDeclaration.containingKtFile
            }
        }
        val name = declaration.name ?: return null

        val actualDir = declaration.containingFile.containingDirectory
        val actualPackage = JavaDirectoryService.getInstance().getPackage(actualDir)

        return createFileForPackage(commonModule, name, actualPackage, declaration.containingKtFile.packageDirective)
    }

    companion object : KotlinIntentionActionsFactory() {
        override fun doCreateActions(diagnostic: Diagnostic): List<IntentionAction> {
            val d = DiagnosticFactory.cast(diagnostic, Errors.ACTUAL_WITHOUT_EXPECT)
            val declaration = d.psiElement as? KtNamedDeclaration ?: return emptyList()
            val compatibility = d.b
            // For function we allow it, because overloads are possible
            if (compatibility.isNotEmpty() && declaration !is KtFunction) return emptyList()

            val actualModule = declaration.module ?: return emptyList()
            val expectedModules = actualModule.implementedModules
            return when (declaration) {
                is KtClassOrObject -> expectedModules.map { CreateExpectedClassFix(declaration, it) }
                is KtFunction -> expectedModules.map { CreateExpectedFunctionFix(declaration, it) }
                is KtProperty -> expectedModules.map { CreateExpectedPropertyFix(declaration, it) }
                else -> emptyList()
            }
        }
    }
}

class CreateExpectedClassFix(
    klass: KtClassOrObject,
    commonModule: Module
) : CreateExpectedFix<KtClassOrObject>(klass, commonModule, { project, element ->
    generateClassOrObjectByActualClass(project, element)
}) {

    override val elementType = run {
        val element = element
        when (element) {
            is KtObjectDeclaration -> "object"
            is KtClass -> when {
                element.isInterface() -> "interface"
                element.isEnum() -> "enum class"
                element.isAnnotation() -> "annotation class"
                else -> "class"
            }
            else -> "class"
        }
    }
}

class CreateExpectedPropertyFix(
    property: KtProperty,
    commonModule: Module
) : CreateExpectedFix<KtProperty>(property, commonModule, { project, element ->
    val descriptor = element.toDescriptor() as? PropertyDescriptor
    descriptor?.let { generateProperty(project, element, descriptor) }
}) {

    override val elementType = "property"
}

class CreateExpectedFunctionFix(
    function: KtFunction,
    commonModule: Module
) : CreateExpectedFix<KtFunction>(function, commonModule, { project, element ->
    val descriptor = element.toDescriptor() as? FunctionDescriptor
    descriptor?.let { generateFunction(project, element, descriptor) }
}) {

    override val elementType = "function"
}

private fun KtPsiFactory.generateClassOrObjectByActualClass(
    project: Project,
    actualClass: KtClassOrObject
): KtClassOrObject {
    val expectedClass = createClassCopyByText(actualClass)
    expectedClass.declarations.forEach {
        when (it) {
            is KtEnumEntry -> return@forEach
            is KtClassOrObject -> it.delete()
            is KtCallableDeclaration -> it.delete()
        }
    }
    expectedClass.primaryConstructor?.delete()

    val context = actualClass.analyzeWithContent()
    expectedClass.superTypeListEntries.zip(actualClass.superTypeListEntries).forEach { (expectedEntry, actualEntry) ->
        if (actualEntry !is KtSuperTypeEntry) return@forEach
        val superType = context[BindingContext.TYPE, expectedEntry.typeReference]
        val superClassDescriptor = superType?.constructor?.declarationDescriptor as? ClassDescriptor ?: return@forEach
        if (superClassDescriptor.kind == ClassKind.CLASS || superClassDescriptor.kind == ClassKind.ENUM_CLASS) {
            actualEntry.replace(createSuperTypeCallEntry("${actualEntry.typeReference!!.text}()"))
        }
    }
    expectedClass.addModifier(KtTokens.EXPECT_KEYWORD)

    declLoop@ for (actualDeclaration in actualClass.declarations) {
        val descriptor = actualDeclaration.toDescriptor() ?: continue
        val expectedDeclaration: KtDeclaration = when (actualDeclaration) {
            is KtClassOrObject ->
                if (actualDeclaration !is KtEnumEntry) {
                    generateClassOrObjectByActualClass(project, actualDeclaration)
                } else {
                    continue@declLoop
                }
            is KtCallableDeclaration -> {
                when (actualDeclaration) {
                    is KtFunction -> generateFunction(project, actualDeclaration, descriptor as FunctionDescriptor, expectedClass)
                    is KtProperty -> generateProperty(project, actualDeclaration, descriptor as PropertyDescriptor, expectedClass)
                    else -> continue@declLoop
                }
            }
            else -> continue@declLoop
        }
        expectedClass.addDeclaration(expectedDeclaration)
    }
    val actualPrimaryConstructor = actualClass.primaryConstructor
    if (expectedClass is KtClass && actualPrimaryConstructor != null) {
        val descriptor = actualPrimaryConstructor.toDescriptor()
        if (descriptor is FunctionDescriptor) {
            val expectedPrimaryConstructor = generateFunction(project, actualPrimaryConstructor, descriptor, expectedClass)
            expectedClass.createPrimaryConstructorIfAbsent().replace(expectedPrimaryConstructor)
        }
    }

    return expectedClass
}

private fun generateFunction(
    project: Project,
    actualFunction: KtFunction,
    descriptor: FunctionDescriptor,
    targetClass: KtClassOrObject? = null
): KtFunction {
    val memberChooserObject = OverrideMemberChooserObject.create(
        actualFunction, descriptor, descriptor,
        OverrideMemberChooserObject.BodyType.NO_BODY
    )
    return if (targetClass != null) {
        memberChooserObject.generateExpectMember(targetClass = targetClass, copyDoc = true)
    } else {
        memberChooserObject.generateTopLevelExpect(copyDoc = true, project = project)
    } as KtFunction
}

private fun generateProperty(
    project: Project,
    actualProperty: KtProperty,
    descriptor: PropertyDescriptor,
    targetClass: KtClassOrObject? = null
): KtProperty {
    val memberChooserObject = OverrideMemberChooserObject.create(
        actualProperty, descriptor, descriptor,
        OverrideMemberChooserObject.BodyType.NO_BODY
    )
    return if (targetClass != null) {
        memberChooserObject.generateExpectMember(targetClass = targetClass, copyDoc = true)
    } else {
        memberChooserObject.generateTopLevelExpect(copyDoc = true, project = project)
    } as KtProperty
}




