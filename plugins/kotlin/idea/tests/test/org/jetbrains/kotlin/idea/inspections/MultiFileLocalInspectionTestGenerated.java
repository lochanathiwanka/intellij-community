/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.inspections;

import com.intellij.testFramework.TestDataPath;
import org.jetbrains.kotlin.test.JUnit3RunnerWithInners;
import org.jetbrains.kotlin.test.KotlinTestUtils;
import org.jetbrains.kotlin.test.TargetBackend;
import org.jetbrains.kotlin.test.TestMetadata;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.regex.Pattern;

/** This class is generated by {@link org.jetbrains.kotlin.generators.tests.TestsPackage}. DO NOT MODIFY MANUALLY */
@SuppressWarnings("all")
@TestMetadata("idea/testData/multiFileLocalInspections")
@TestDataPath("$PROJECT_ROOT")
@RunWith(JUnit3RunnerWithInners.class)
public class MultiFileLocalInspectionTestGenerated extends AbstractMultiFileLocalInspectionTest {
    public void testAllFilesPresentInMultiFileLocalInspections() throws Exception {
        KotlinTestUtils.assertAllTestsPresentInSingleGeneratedClass(this.getClass(), new File("idea/testData/multiFileLocalInspections"), Pattern.compile("^(.+)\\.test$"), TargetBackend.ANY);
    }

    @TestMetadata("convertSealedSubClassToObject/convertCallableReferenceUsages/convertCallableReferenceUsages.test")
    public void testConvertSealedSubClassToObject_convertCallableReferenceUsages_ConvertCallableReferenceUsages() throws Exception {
        String fileName = KotlinTestUtils.navigationMetadata("idea/testData/multiFileLocalInspections/convertSealedSubClassToObject/convertCallableReferenceUsages/convertCallableReferenceUsages.test");
        doTest(fileName);
    }

    @TestMetadata("convertSealedSubClassToObject/convertInOtherFiles/convertInOtherFiles.test")
    public void testConvertSealedSubClassToObject_convertInOtherFiles_ConvertInOtherFiles() throws Exception {
        String fileName = KotlinTestUtils.navigationMetadata("idea/testData/multiFileLocalInspections/convertSealedSubClassToObject/convertInOtherFiles/convertInOtherFiles.test");
        doTest(fileName);
    }

    @TestMetadata("moveFileToPackageMatchingDirectory/moveToDefaultDirectory/moveToDefaultDirectory.test")
    public void testMoveFileToPackageMatchingDirectory_moveToDefaultDirectory_MoveToDefaultDirectory() throws Exception {
        String fileName = KotlinTestUtils.navigationMetadata("idea/testData/multiFileLocalInspections/moveFileToPackageMatchingDirectory/moveToDefaultDirectory/moveToDefaultDirectory.test");
        doTest(fileName);
    }

    @TestMetadata("moveFileToPackageMatchingDirectory/moveToNonDefaultDirectory/moveToNonDefaultDirectory.test")
    public void testMoveFileToPackageMatchingDirectory_moveToNonDefaultDirectory_MoveToNonDefaultDirectory() throws Exception {
        String fileName = KotlinTestUtils.navigationMetadata("idea/testData/multiFileLocalInspections/moveFileToPackageMatchingDirectory/moveToNonDefaultDirectory/moveToNonDefaultDirectory.test");
        doTest(fileName);
    }

    @TestMetadata("moveFileToPackageMatchingDirectory/packageMatchesDirectory/packageMatchesDirectory.test")
    public void testMoveFileToPackageMatchingDirectory_packageMatchesDirectory_PackageMatchesDirectory() throws Exception {
        String fileName = KotlinTestUtils.navigationMetadata("idea/testData/multiFileLocalInspections/moveFileToPackageMatchingDirectory/packageMatchesDirectory/packageMatchesDirectory.test");
        doTest(fileName);
    }

    @TestMetadata("reconcilePackageWithDirectory/addQuotation/addQuotation.test")
    public void testReconcilePackageWithDirectory_addQuotation_AddQuotation() throws Exception {
        String fileName = KotlinTestUtils.navigationMetadata("idea/testData/multiFileLocalInspections/reconcilePackageWithDirectory/addQuotation/addQuotation.test");
        doTest(fileName);
    }

    @TestMetadata("reconcilePackageWithDirectory/changeToDefaultPackage/changeToDefaultPackage.test")
    public void testReconcilePackageWithDirectory_changeToDefaultPackage_ChangeToDefaultPackage() throws Exception {
        String fileName = KotlinTestUtils.navigationMetadata("idea/testData/multiFileLocalInspections/reconcilePackageWithDirectory/changeToDefaultPackage/changeToDefaultPackage.test");
        doTest(fileName);
    }

    @TestMetadata("reconcilePackageWithDirectory/changeToNonDefaultPackage/changeToNonDefaultPackage.test")
    public void testReconcilePackageWithDirectory_changeToNonDefaultPackage_ChangeToNonDefaultPackage() throws Exception {
        String fileName = KotlinTestUtils.navigationMetadata("idea/testData/multiFileLocalInspections/reconcilePackageWithDirectory/changeToNonDefaultPackage/changeToNonDefaultPackage.test");
        doTest(fileName);
    }

    @TestMetadata("reconcilePackageWithDirectory/innerClass/innerClass.test")
    public void testReconcilePackageWithDirectory_innerClass_InnerClass() throws Exception {
        String fileName = KotlinTestUtils.navigationMetadata("idea/testData/multiFileLocalInspections/reconcilePackageWithDirectory/innerClass/innerClass.test");
        doTest(fileName);
    }

    @TestMetadata("reconcilePackageWithDirectory/packageMatchesDirectory/packageMatchesDirectory.test")
    public void testReconcilePackageWithDirectory_packageMatchesDirectory_PackageMatchesDirectory() throws Exception {
        String fileName = KotlinTestUtils.navigationMetadata("idea/testData/multiFileLocalInspections/reconcilePackageWithDirectory/packageMatchesDirectory/packageMatchesDirectory.test");
        doTest(fileName);
    }
}
