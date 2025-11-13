package org.starexec.test.junit.data.security;

import org.junit.Test;
import org.starexec.data.database.Users;
import org.starexec.data.security.GeneralSecurity;

import static org.junit.Assert.*;

/**
 * Test for admin picture upload authorization logic
 */
public class AdminPictureUploadTest {

    @Test
    public void testAdminCanUploadOwnPicture() {
        // Test admin user (ID=1) uploading own picture
        int adminUserId = 1;
        int targetUserId = 1; // own picture

        boolean callerIsOwner = (targetUserId == adminUserId);
        boolean callerIsAdmin = GeneralSecurity.hasAdminWritePrivileges(adminUserId);
        boolean isPublicUser = Users.isPublicUser(adminUserId);

        System.out.println("Admin upload own picture test:");
        System.out.println("callerIsOwner: " + callerIsOwner);
        System.out.println("callerIsAdmin: " + callerIsAdmin);
        System.out.println("isPublicUser: " + isPublicUser);

        // Authorization logic from UploadPicture.java line 74
        boolean shouldDeny = !(callerIsOwner || callerIsAdmin) || isPublicUser;

        System.out.println("Should deny: " + shouldDeny);
        assertFalse("Admin should be able to upload own picture", shouldDeny);
    }

    @Test
    public void testAdminCanUploadOtherPicture() {
        // Test admin user (ID=1) uploading another user's picture
        int adminUserId = 1;
        int targetUserId = 2; // another user's picture

        boolean callerIsOwner = (targetUserId == adminUserId);
        boolean callerIsAdmin = GeneralSecurity.hasAdminWritePrivileges(adminUserId);
        boolean isPublicUser = Users.isPublicUser(adminUserId);

        System.out.println("Admin upload other picture test:");
        System.out.println("callerIsOwner: " + callerIsOwner);
        System.out.println("callerIsAdmin: " + callerIsAdmin);
        System.out.println("isPublicUser: " + isPublicUser);

        // Authorization logic from UploadPicture.java line 74
        boolean shouldDeny = !(callerIsOwner || callerIsAdmin) || isPublicUser;

        System.out.println("Should deny: " + shouldDeny);
        assertFalse("Admin should be able to upload other user's picture", shouldDeny);
    }

    @Test
    public void testNonAdminCannotUploadOtherPicture() {
        // Test non-admin user trying to upload another user's picture
        int nonAdminUserId = 2; // assuming user 2 is not admin
        int targetUserId = 3; // another user's picture

        boolean callerIsOwner = (targetUserId == nonAdminUserId);
        boolean callerIsAdmin = GeneralSecurity.hasAdminWritePrivileges(nonAdminUserId);
        boolean isPublicUser = Users.isPublicUser(nonAdminUserId);

        System.out.println("Non-admin upload other picture test:");
        System.out.println("callerIsOwner: " + callerIsOwner);
        System.out.println("callerIsAdmin: " + callerIsAdmin);
        System.out.println("isPublicUser: " + isPublicUser);

        // Authorization logic from UploadPicture.java line 74
        boolean shouldDeny = !(callerIsOwner || callerIsAdmin) || isPublicUser;

        System.out.println("Should deny: " + shouldDeny);
        assertTrue("Non-admin should NOT be able to upload other user's picture", shouldDeny);
    }
}