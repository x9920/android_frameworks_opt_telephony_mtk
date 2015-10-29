/*
 * Copyright (c) 2014, Linux Foundation. All rights reserved.
 * Not a Contribution, Apache license notifications and license are retained
 * for attribution purposes only.
 *
 * Copyright (C) 2006 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.telephony.uicc;

/**
 * {@hide}
 */
public interface IccConstants {
    // GSM SIM file ids from TS 51.011
    static final int EF_ADN = 0x6F3A;
    static final int EF_FDN = 0x6F3B;
    static final int EF_GID1 = 0x6F3E;
    static final int EF_SDN = 0x6F49;
    static final int EF_EXT1 = 0x6F4A;
    static final int EF_EXT2 = 0x6F4B;
    static final int EF_EXT3 = 0x6F4C;
    static final int EF_EXT5 = 0x6F4E;
    static final int EF_EXT6 = 0x6fc8;   // Ext record for EF[MBDN]
    static final int EF_MWIS = 0x6FCA;
    static final int EF_MBDN = 0x6fc7;
    static final int EF_PNN = 0x6fc5;
    static final int EF_OPL = 0x6fc6;
    static final int EF_SPN = 0x6F46;
    static final int EF_SMS = 0x6F3C;
    static final int EF_ICCID = 0x2fe2;
    static final int EF_AD = 0x6FAD;
    static final int EF_MBI = 0x6fc9;
    static final int EF_MSISDN = 0x6f40;
    static final int EF_SPDI = 0x6fcd;
    static final int EF_SST = 0x6f38;
    static final int EF_CFIS = 0x6FCB;
    static final int EF_IMG = 0x4f20;

    // USIM SIM file ids from TS 131.102
    public static final int EF_PBR = 0x4F30;
    public static final int EF_LI = 0x6F05;

    // GSM SIM file ids from CPHS (phase 2, version 4.2) CPHS4_2.WW6
    static final int EF_MAILBOX_CPHS = 0x6F17;
    static final int EF_VOICE_MAIL_INDICATOR_CPHS = 0x6F11;
    static final int EF_CFF_CPHS = 0x6F13;
    static final int EF_SPN_CPHS = 0x6f14;
    static final int EF_SPN_SHORT_CPHS = 0x6f18;
    static final int EF_INFO_CPHS = 0x6f16;
    static final int EF_CSP_CPHS = 0x6f15;

    // CDMA RUIM file ids from 3GPP2 C.S0023-0
    // RUIM EF stores the (up to) 56-bit electronic identification
    // number (ID) unique to the R-UIM. (Removable UIM_ID)
    static final int EF_RUIM_ID = 0x6f31;
    static final int EF_CST = 0x6f32;
    static final int EF_RUIM_SPN =0x6F41;
    static final int EF_MODEL = 0x6F90;

    // ETSI TS.102.221
    static final int EF_PL = 0x2F05;
    // 3GPP2 C.S0065
    static final int EF_CSIM_LI = 0x6F3A;
    static final int EF_CSIM_SPN =0x6F41;
    static final int EF_CSIM_MDN = 0x6F44;
    static final int EF_CSIM_IMSIM = 0x6F22;
    static final int EF_CSIM_CDMAHOME = 0x6F28;
    static final int EF_CSIM_EPRL = 0x6F5A;
    static final int EF_CSIM_MODEL = 0x6F81;
    static final int EF_CSIM_PRL = 0x6F30;
    // C.S0074-Av1.0 Section 4
    static final int EF_CSIM_MLPL = 0x4F20;
    static final int EF_CSIM_MSPL = 0x4F21;
    static final int EF_CSIM_MIPUPP = 0x6F4D;

    //ISIM access
    static final int EF_IMPU = 0x6f04;
    static final int EF_IMPI = 0x6f02;
    static final int EF_DOMAIN = 0x6f03;
    static final int EF_IST = 0x6f07;
    static final int EF_PCSCF = 0x6f09;
    static final int EF_PSI = 0x6fe5;

    //plmnwact
    static final int EF_PLMNWACT = 0x6F60;

    // SMS record length from TS 51.011 10.5.3
    static public final int SMS_RECORD_LENGTH = 176;
    // SMS record length from C.S0023 3.4.27
    static public final int CDMA_SMS_RECORD_LENGTH = 255;

    static final String MF_SIM = "3F00";
    static final String DF_TELECOM = "7F10";
    static final String DF_PHONEBOOK = "5F3A";
    static final String DF_GRAPHICS = "5F50";
    static final String DF_GSM = "7F20";
    static final String DF_CDMA = "7F25";
    static final String DF_MMSS = "5F3C";

    //UICC access
    static final String DF_ADF = "7FFF";

    //CM-Specific : Fake ICCID
    static final String FAKE_ICCID = "00000000000001";

    // MTK
    static final int EF_ECC = 0x6FB7;

    // USIM SIM file ids from TS 31.102
    static final int EF_PSISMSC = 0x6FE5;
    static final int EF_GBABP = 0x6fD6;
    // [ALPS01206315] Support EF_SMSP (EF ids from 11.11/31.102)
    static final int EF_SMSP = 0x6F42;
    static final int EF_ELP = 0x2F05;
    // ALPS00302702 RAT balancing
    static public final int EF_RAT = 0x4F36; // ADF(USIM)/7F66/5F30/4F36
    static final String DF_USIM = "7FFF";
    static final int EF_GID2 = 0x6F3F;

    // MTK-START [ALPS00092673] Orange feature merge back added by mtk80589 in 2011.11.15
    /*
      Detail description:
      This feature provides a interface to get menu title string from EF_SUME
    */
    // SET UP MENU ELEMENTS
    static final int EF_SUME = 0x6F54;
    // MTK-END [ALPS00092673] Orange feature merge back added by mtk80589 in 2011.11.15

    //ISIM access file ids from TS 31.103
    static final int EF_ISIM_GBABP = 0x6fd5;
    static final int EF_ISIM_GBANL = 0x6fd7;
}
