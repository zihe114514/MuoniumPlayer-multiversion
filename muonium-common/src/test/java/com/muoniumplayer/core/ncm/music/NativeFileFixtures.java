package com.muoniumplayer.core.ncm.music;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.zip.InflaterInputStream;

/** Synthetic 0.15s sine fixtures generated once with FFmpeg; tests need no executable/network. */
final class NativeFileFixtures {
    private NativeFileFixtures() {}
    static byte[] audio(String name) throws IOException {
        String value = switch (name) {
            case "mp3" ->
                    "eNrllVtYU1cahndCSEIMCQEhBCQJkXMh7nAqWCoRiUKIioRMkSJuTkGrBNCqKFKCRJvKoTCCUBFEi+hElCkB0cExiZxFPFBAy6FgUURFoGhQsL" +
                    "CbDDLN1czVPHPR92LttW7W/p/3+df/Bfq7oQAtK0P5fI7mSwQAPV7UfqGnG5PlyWSBIPAH8PsvY4H/QqBImLh4DYAFABwd8NJBoEOyLrk6yHRp" +
                    "1WFEF1gH7X81NcdoanZ11y3GRr98cYOjd8SXWQL/Yxb9IEOMggpjlZqzIb0cQBj5qG0pz8Wao3umZsHIGiv35vzICx11o37i5XtzDfNjKXvmZN" +
                    "7YleKXVqUNU+8Wr0KgGOPTsaqpuQyA/RSa/e1s++b0DYrvhx5Mzq8VH56H2K0YJXI5ABgAYILJiF2dDxiLk9t7NIAkHH8leoNbOcEEMRXgegrt" +
                    "hWof8B7F26iuqSQW5AuVCWLWbqIJf3jHju100SpVnWSPRf1U2/crHKyXETYvMLeE5z2+S4mOh1vd4wKSnhGSXTnpMOM8w5mTHVTRXgTP5ucVmK" +
                    "ElincBFsadMggPsKUqH2o2mT1+q9zxTqptxLQoNQfTlteC98dTlBKluyGPgohQYY38EGpjrR+ydNoE00xMAVBaP8MAAn3NyqUq1Y+u8aORhEre" +
                    "PG/MQJCSDm+A36hh+Hha2WU0PDHXMhjtNHbT3s3cU//6OSCFJh4JLBxJEl1uv7J1Wxj5gCAHhZb/Q6Z4NbdzBlb/NPZw5oAA6ACQ0ANQeg4VbF" +
                    "6NjspwbukLQ0Y1ubXgrDclOlqMrB/Arlb22cT8XT2WlvprcmyGUvHDmXVV8rjSgP78hjxhbcQpz7mxFkz1e2ccgcCi7rHHsuoB/stgIwT1oCRY" +
                    "j/pFsUxqZPup7w7LyF3pka9RtIHua4J96b1XsqyWCydfzPuOzzjR8bxPwOd5Tcvk9Em8t1P3cMnObJ+vI5SxLvhseQgqqO2jpNNIqFYY2gnXbt" +
                    "H62Yqd9sY0ycvu52n9aBoBU25l6fKewf7gJ048qqgrK2oOHcxJVa8UUt4aOUbnJ8nuGV542HW24CyV5JrkFsb08XYzUK4ZCQkOluPu4xr1ePom" +
                    "KuAB6LrzDkOPQWrF1rS79FeTo+xoc6/g42pr2ntFVl8nPCPgrd2oGQKFBQDPz4FljWqNz/yUy4vbxp5pEdwRNd1XXaTc2YdoKqmw5GS+FvRGXu" +
                    "3+uZbDeXHo5qqYNNrbyZnnivlDzAVFsDs1+hyphC98ozSHnlwqcYybMd813F3MUG5fUTJbefEUQlVUfEocbsbP4X8U5zEenByDezMuyE4j1FDu" +
                    "gc0HK7uvV0vDK20DdxU8fHTzSsZtbVnuTFDrp+CbaR6madkffrDLrUxdYL8lP7L/DEgGtoFWyi9BprgRDFfdYJng5F5TOedYLhmNYBhbShvzBn" +
                    "o2eu7N/VUF1fxScoHzonRLLYMSss5J8XaCK2P0UM7sG2RWXAj/22CD53i1vyChVNZ7vcLpskn4ox5qRDvn441yO6Q/u/DpehBgO4OAMgYkAG2g" +
                    "rWoI/CrriDsrtxrMPYIGQXb/mqHcmJEyyZAZootB9tp7teqlcCu5e5UcvTnL2ECyCe6/dXnbIBeuqxc+gV2zfO29E17T1P+EJzO3d6639LCLIr" +
                    "oSfx59Ou9I6OwBeBePQLR/zZ8O7LQppjmorG3Jj8E7GoGQHr3kpwrSR3Ch/CNmUKrELCo3Z7do4GtqdORRHPQ4kwsViPsgekYkxBd3QY7iMggp" +
                    "tqsKub0prNmqsJGww2kFOxTTv9zGoT4LbXOVSEy06XX4SWxtKily27PbqLf7PEYMHm37y63a7wJVw4Ae4oRxfnjPWu04i/Hi7UdWfMEOO3jmCR" +
                    "QWYG5YOsc4hoznZNhtOMYetlECPMWYCO2C90a1ifWfIdc8GclsCil6/DDBWl5KEqq63PefcL46zb17cm2nuCozqsSsLPCr8nVHk1ijeeu/Od/x" +
                    "10ZPhzjvF61JN5Bgsym1g73V47NbryITac98ZyfWff5tA2HgpDk8pPUzLn29Osgvc45U/sGP4Y144qoxofSDnwV4Fr63wJ3ort5//pBiMV/uLa" +
                    "Rx1aLPLu1NGYBfGhhw/DZ+aEjB/w09Y68WyPW7FLow5ph+DsI/0rRr4u7tM2N1+NWnSXoyrvLR6WM2LdExpAcn7hfP9dzoelaXWFNf09vXwcxG" +
                    "Hy9czaAeKHSyGL6Uxf3NIqVQ533BYr1UbGPdYWgpv8geFPvrA//OL8GfGK2fi99Oi1bIiVHipfzStFCFxstSfv2Z/fwOpZn3yQ==";
            case "aac" ->
                    "eNr7/9Gngf/+n3tMDD6JZclmxnpGJnqGBgYMThHz////f4CBVduigNvwUWX2zSXcOyY9mZgpxGxtbR12z1TWTU1d2doKyOHm5q788nKVgJaTQG" +
                    "t2IGNAa3aIIod2kEDYVhCfAUgECTAyCDRtFRAVaHDkAAprrWCQ+9nAwHjgP9B2Lvk/ih7v/v+/tEk2paLu17e9hev/ytW8jrqwz/s5/9LNGgYB" +
                    "Biw+huqCrQKJLvIMgbsEnlf98Fbru1yhaKOh3MRjwWPB2MLikMjArnjiAcg8nvl/FGv8gY5/wMCU0J6m1MeYVfH0y/Tl3pmrL3lcCRG5YP/h5i" +
                    "pL+R/nPRedW7bkZFu+/f4d9scPVG6Q2DCzQOSp+Owj1pwMGxYcCFERZOgQYRDiaBBmcuCexCDKwHzfgIHBAmQHN9AOGagdHO3J8i1i5b+r6uxP" +
                    "zHLuS4hT103qZnEV0CpyYy5MLUleHSLQ6rWQNatVVCGkVLgxIFKtIESLwSGGi6nBxYHBRaiB9RQDNwPj+xkMDDLoZrNyJjrLHUyvv/3pf/53tm" +
                    "nC5keTtAIymJycal6cOm72bqnPtMyJPTlud7IEt7asEeNgYW1z0GFlbQhgcWBxcWBRCWhgPeDC3YBk9n2E2Sxgd9+3P77nf/0dKZlckX0pCVIL" +
                    "P80rcL60hOWnwhqT+/vrfmlk7hW5t2Cj0JMVFk1KR1RYWxk4mhw6GDoaGK0bGTin6TEmMNh+ZmBgbgCHvD3cdMa/7MlK8w6mn1//sv79n6aHNT" +
                    "vO7OzcuiIi9m3eTIEXTooxk0IKbD6f9ysJ+qKe5Ves4ZtUzLHGYUNLwwJRRsEAQZZGQQFGAQXGBR4MMQz8NQcYGMBRK1aPcL7XZTY3jglJCdHS" +
                    "t2+ybC8s27q2P7lQoep9c6H/xAJ+5zf8m691qBqZL/3hN/XhzW+3DmWty388c35neKCfm2RQVFpJxPXFR3IbTl9v7D2tISm9yMnPZ4LB1ocHLp" +
                    "3q4em9qGjsM8HoK4jHxl72YvJkX6n8e2EMQBVGXx8kFy1mVi574XNZhUe36uPOUkfN3on5e0/wJqdEHw6KerbtxOwDSGHChRTirDKGDB1OrLws" +
                    "Ly5rFa7ddTz6sQIn5+6ytxcMDIyNTussOHBkfX6O6VRH5grTrldXzBu0Vwcwx23atcCBkzdqAdcBBvZ9CQwMBQCzoY4o";
            case "m4a" ->
                    "eNpjYGCQSSupLPA1cVRgYGBiANGZxfm5QGzEwMDAkVaUmsrAwFydm5JYco+JwSexLNnMWM/IRM/QwIDBKWL+////DzCwalsUcBs+qsy+uYR7x6" +
                    "QnEzOFmK2trcPumcq6qakrW1sBOdzc3JVfXq4S0HISaM0OZAxozQ5R5NAOEgjbCuIzAIkgAUYGgaatAqICDY4cQGGtFQxyPxsYGA8oerz7///S" +
                    "JtmUirpf3/YWrv8rV/M66sI+7+f8SzdrGAQYsPgYqgu2CiS6yDME7hJ4XvXDW63vcoWijYZyE48FjwVjC4tDIgO74okHijX+QOc+YGBKaE9T6m" +
                    "PMqnj6Zfpy78zVlzyuhIhcsP9wc5Wl/I/znovOLVtysi3ffv8O++MHKjdIbJhZIPJUfPYRa06GDQsOhKgIMnSIMAhxNAgzOXBPYhBlYL5vwMBg" +
                    "oSgDNZyjPVm+Raz8d1Wd/YlZzn0Jceq6Sd0srgJaRW7MhaklyatDBFq9FrJmtYoqhJQKNwZEqhWEaDE4xHAxNbg4MLgINbCeYuBmYHw/AxgzcE" +
                    "NZOROd5Q6m19/+9D//O9s0YfOjSVoBGUxOTjUvTh03e7fUZ1rmxJ4ctztZgltb1ohxsLC2OeiwsjYEsDiwuDiwqAQ0sB5w4W5AM5QF7NL79sf3" +
                    "/K+/IyWTK7IvJUFq4ad5Bc6XlrD8VFhjcn9/3S+NzL0i9xZsFHqywqJJ6YgKaysDR5NDB0NHA6N1IwPnND3GBAbbz8AU0gAzlvEve7LSvIPp59" +
                    "e/rH//p+lhzY4zOzu3roiIfZs3U+CFk2LMpJACm8/n/UqCvqhn+RVr+CYVc6xx2NDSsECUUTBAkKVRUIBRQIFxgQdDDAN/zQEGhgdwB3tdZnPj" +
                    "mJCUEC19+ybL9sKyrWv7kwsVqt43F/pPLOB3fsO/+VqHqpH50h9+Ux/e/HbrUNa6/Mcz53eGB/q5SQZFpZVEXF98JLfh9PXG3tMaktKLnPx8Jh" +
                    "hsfXjg0qkent6LisY+E4y+gnhs7GUvJk/2lcq/F8YAVGH09UFy0WJm5bIXPpdVeHSrPu4sddTsnZi/9wRvckr04aCoZ9tOzD6AFgpMrDKGDB1O" +
                    "rLwsLy5rFa7ddTz6sQIn5+6ytxcMDIyNTussOHBkfX6O6VRH5grTrldXzBu0Vwcwx23atcCBkzdqAdcBBvZ9CQwMBUAjxXPz88uAWTEntywjhQ" +
                    "EF7G4ARqcCAyMDCCEAI6oqdL4DA17ABESOJUWJ2UB2TEk22E5mTNOA9iKJMZJur0pqSkkxyKDUnOIShA6QuSxQvYw7c1MyE4EMhdwUbH5XYQg9" +
                    "AuboZqTkFMFkivNL85BVBgP5KR6JeSk5qSA1jCm5mXlpQIZAcS6KoSopEHGZlKLUNCQv8JQW5ShA2IwaxSVJOUB2VnFJcQqSmqjcApNEDG8zMQ" +
                    "jAnAoEZqnFKSAPA1NJQ4MqUBkLkBZ3EAVL/gLi/u2sQBFWwQlhTxnYgCxGYFQwSBSXgIMJZjInOHiArgS6IBlJHCoHpi2AclVIPgOJlwOxJxDH" +
                    "AnEoFIeDXA7Ea4DYH4hFikuS85HM1AFiqeL0ghQQpyg/JweSPBgY//8HuyApvYABIYPsgqTSlJJEcLiklsDCRRE5koARW5RYUJCDHFG6mZCUoL" +
                    "qyJB/kDFlgnZMIC1BgzZMGrHkMzUA1DwCkPdHb";
            case "vorbis" ->
                    "eNrtWHlUU8can0DAsBog0IDRBkwkCdEmGCyx0CYpCEQRCEQ2YxFkc6kI0kJrNZhAMQ+R0pBi5FFAkkIUDlClVGprFdxldeEUd6og+lqr9aDv+e" +
                    "z77g1UrT2n57z2z07m3vvNzLfM/L653/0mEenp0cgCTRaL0pBvsOcDbvVlwizCO+uzkzNz8AFl5yTLuwT80UGIwCTRs5LYSC016QZZ9Mtz5bql" +
                    "WZEDjC9Z+U7agvnz+Avm8Xk8TOBluFLfTlm/KjU7EAZTYNBXgA3S12Ymm+UIVuYnU/LmMgISIcTIoXIWGXLc1GQPSaT7CE3Ss+aOu2SJ01aa75" +
                    "LEU8ycMs/6GklxIqugr0yOMIEPxXkUtUZM8lQzA+PKVd6BQBULoS8T6yNLpm7BwgwYFZNmqkd4/vaqG/4PadAXR4a+Mbp6uqRDr9orBD6GX2ik" +
                    "ylo4RlMf5rI0W7t4HW5qoh+rZOv3vONnVXaSYr0qzO+hhzpdnOdmtsEQh5aprMXHqepFYpiBtbCDoj4lbvBQc4Skl9V0wSpXVIZQTR1LU8eyJX" +
                    "u02aLjCJFFUZRjG2gnPqKdaKXZkRzirRAiIBLwhWrqQ0u/Dy1nxJcz8Xpzm4l7qg2vvrh4KEKRUfbBGyjTcLlVCJFq/DWGOI0hQzNaXEpSF+21" +
                    "QEqEiGBG8GZ0QIg2qDdGfqF93fAzNVGX6o6LA4bkgcy0RutinqW7Ne4HmIbSScPrUk6BrKRKHetsmoRXqkZq4h310lbhzKrQ/fnluzRfCkercn" +
                    "pO3Kzi9wBVVxVvrwe+0aqumnhbM+Xdmt+n52DUhp5bSVVOR4Ga05Rfpt++HyirpsxS/eJWoEK/zJftWrwf1zd1G+++RatywaiGg1/qdvV1AxVx" +
                    "9NblKnLPc3w4yLBeEblo+8qLbNLAaAZ9kVOyh+8t8cQYz8ZDVSUZ7x/LqNcuJDErNsik3mbALZCI5FlIkYVO3ZKLPYuo2SfZn9VslXIaG+pKfE" +
                    "9xPz+N3xbe2fu542v2/C7ymz5NXbLodX7n49b8IBzVq/YtS1jTtF8mZRu0MlscPzrMRWLtvqosyqyU/VlVti2nwQwnHXMNQhH3aP1OM5/Wn2i9" +
                    "TswoZ6hzB339BqOD5bmpF3S58V98kDhUqJj44BLUh7kr43Sp8c9V89LhNafDPjcvhH2UmnNaOMbQkqeWCQ5FWZQQfL+FtTEWDzDODHAj7gsiXA" +
                    "KjKMGDAnm/qyLq1XVRC1IH/RTRCxTyZetidesS23OHP0kdnvjgyjNVMQ1fojNCC8tns8u92OUj/qWMMFHGcQ4SWWObRXOdVTriX84IK2dmlqfn" +
                    "6xp26m/trOaYcBZExliwojHmaRrzNA15pWPF5Rk7dQ2Nep+TxjsHmm//UbXDNzgDoY2MkGxGiIwRLAMCR1dpj6FLO8JnBGdzTw0IzGvsdwkEQG" +
                    "M3FjzrAITsT7RQTvXTwp2Z52Rz5dFzz8mCz0XL/6iEDwrCXQBYG1RGQDXkxMVN3RdtVtOSB7JPMzYuTSabWkleIfu/OMk30LqrPYq1Uc7vm1gp" +
                    "mrDT/H19bO+m87KFhZT6ymhbV5ljAjivEJH1hKtZNFEbV9QmmAy5ZQKHvoDpLQHTJ9uMcEtuhKWg1xL9+SIaEIjuB0CdbIOhu0FOUCfb3F7LgH" +
                    "7LgCjiX2DrUP/ztlrMtgKmbMGKwNBfY+vv8nf5g0KGehWuJE+KR5sXKnZBPH9HgYPeutfSX2fVqxoVUwilqd/ev5pPDn4qJehVmSTyayZ05n6S" +
                    "KVLeMO/pkG5ULK81ZSmUN/J58kpOjOvToQhK7YFIRQfn0PzgDs5yV5Pd708JIjM1Cx68WvupOWX5a1IJSDkCU0KodCTODaE4HVODUFK8nmXxZz" +
                    "F43qAzKrZAPBYYhPQiKV5nJTjThnTWfQHKUQJEBGBmYcwt4ujkDUoI/BYoCiHr4FZuuEuw3pipn6MRYPGQkl9tzK/OlAwG9Ao8NdXpenJw7/zw" +
                    "SiPLxCEHywGVSBx2lbHrOios2ktEEgtkX5ocX+0NOZHG2GgCLQERlOB+gfycPFMPuiBZyjSZxu8FgKFB+ERB/lJo3FaXqce6bx++H9QvCIdYfS" +
                    "73Wn5dhh40wIeBHBwFeoAzV0HXwORM4wfa7IERgrYiVlfA0htNIHvSl4czVSjirSuNpgPNkFaJ5IoLuanx7QQQafY51eaQpbhQAe11CGutHQbU" +
                    "HWnwIS2p+biE84+WfrXddFGtp4+FkgUxjmqkRJ/kzfGsJ3scZaidJXOYFVqpiV4vk7Fd1TEyDnN3uWw1W06WsdkVthKOoSIGoaECS7gKIzOOSx" +
                    "F9OlLSF8FHO8Xe10hJ6RFOq5Bp+KNVH5fwj1bm9ISx3VNOC0frtSVYtiOT3sRSHrYBz3u0Min0LZWymeoeobdBOwB9LgjF4GjvPn6TOFuMwy0j" +
                    "ANwp8eAxQFzHyNSPAsJ6Y1qY/mY+9BhP3J9Ev2J0EnrOBVfIC8IBujbuJJZm2HJTK42YCw7MWzscA/inurc36U2czjb75bGQsbQXwAf6znftCN" +
                    "0lTkM1FjOWhfizPey4glVM7HspoqpKIBsvJEtsPdVaGcnmN7sxFLZ8Wet5LKE6LwrBMippY1aJObf69SZ9vvl/36zQPLk/Gnt9qGXh4bD+uCTw" +
                    "KEEZg3nUUJm9VMJ2lZQI53ga3HzUVMBYynfrFU6r1Z5P4FbnOErYTd1xMeNVWm2CpqmB4stmG84nkw9Dc41hP13NaezSJawRkxTRq9m6oa3k9v" +
                    "ZyWQnf+ppqR2tF3Gr71x/SE3345xIQSlNZoqCtDntmV1G2W7lhEAAm9JIoDR+SOHAxI6WEDx6HI0ztqhLYFGCZ5KnFE1/8WCOTHjP3qSNl42Y+" +
                    "NVnK+ae5TyYNM5j7IiVhTMNZIX4agrlqsVFnCYsJOOB9rNp6yF5BQkIyaDGACslSoMplYWyDs9QGBjCKIiExYZCDOcGmVo3hCGJST22khA2eo/" +
                    "lD4ksVe7vL3VbutnlpljliwPlE2yaTsprkIi8Dtf47SchOFSVnGzfHPuGMIdmRbd1UBMkSdZ+bL2mnbInUhpm7hO09t7sStnZ9Zc52WACELTFS" +
                    "KuEUZUm0mo8OXUXXcsgbLYghM+DNCUorDjoiycqLtCE7RRNEyVsl81BM3PJ6xXLRJ4R5Y9ebLT2JniQx17FGSaRVZR1xWZ932GFpH5uTS8cDoh" +
                    "26C3tuOXJy6j+9clXG2qyNeZuU6uKSMhI+7IY2Qf7FFAUFCXKo7h4zaDNnvUz39JrNgBBtPl3TlYhINGcwWJtIxI/PRDrt6fEZ63+Jv8zPhpkg" +
                    "FoslEqUp5tqeA5wbnE3yWMNXPuPyyAJEX9TltMV489yeodC1DOdtQV4fkRKGDtorrFpGLyumXW6MiubeWVHUbDGU82Oz5Y9nG9jvWQxbIpHtbw" +
                    "P8TTvrTc2vXJ2wqsgloOu8gLNpHqsrizJbE9J6KoUpC3c6jvCM7BVjlxJiCR2XOnttrtx54yzF7mDibQ764v0J4bVd//1Q70V8MdNUm4go1HBx" +
                    "3bIPg8HVkwoCMQUXbX9VkI4pSCz4XQX1CwnIa/xabbOxEeEKvgMFb3TucLjy8KXIaO7Pb/9rD7r46aVjResf2zWwJbwXFChLbFGf23tutOpk0L" +
                    "557GBDLKEen8HDiBpj3m/apG+tX9AQdBahnx+Utif5dzlOraEhsHOHzdQUmvags9WXjqnNU2ghvKAhfhpCe9MCAu/yHACFr4cOFsY+uX/n9eq7" +
                    "pU+m+3V3/zD3q3ufpk/8pH735y0N7HFHJPpFTCaKcVE4p2XM/zy9V+8391D3kSMtia7yGPmDB7cvdHYWLUjuItl1wB8qPrzkrmInt01PgL4SMo" +
                    "OPtx5hf7XsUVY6zeYSHv7nNcSlvybyVyJ/9HrIJcdOpy3DTW/lScI3lB6Y+c7sJ+2J39xV6+v697kssf73kZXUopVUeHfyD5a+enVD4IodF0Ym" +
                    "MuvD0nR73Xdiv/i29DM6v9nbB0I/FoR5D57Y6Av7wzFFbxsT+MpbX296dM+wIrV6YiIlLU35CCv5eVug7Nh9phfK8N0nUBwHHz9+vMkduCbeLc" +
                    "jcvHnzrA6gjI8e+R46eOzbeoaIcBVZ/g+kyaVw";
            case "opus" ->
                    "eNoByQc2+E9nZ1MAAgAAAAAAAAAA03Z2jQAAAABhW7EPARNPcHVzSGVhZAECOAGAuwAAAAAAT2dnUwAAAAAAAAAAAADTdnaNAQAAAFmfOfcBPk" +
                    "9wdXNUYWdzDQAAAExhdmY2My4xNi4xMDABAAAAHQAAAGVuY29kZXI9TGF2YzYzLjI0LjEwMCBsaWJvcHVzT2dnUwAEWB0AAAAAAADTdnaNAgAA" +
                    "AO1O7TkK/zfHz8jPxcj/LPx+Apnn+ez1EdYT/Tr89Tx0eEYBvbY8LhQiXIL5CZirXz40BlqkBiu8IwL7kiuBf/ZndWI7A0H/IugdifotVNjjd4" +
                    "g/IDp8NpigAJh1WGKTtfMNNmsvMuu8L6d5kLcUtnDBF3+uZRroIlHlJDQAfR2oUJXlHF1FYz56LslqjOvxCS/CQx9qU8hQghHxUOlMR2IVpbLv" +
                    "CsJ/eEI0ayA0S6qWGG4Kja/4aTwZ3rEfaHyuoct9ID6f6dSWoMzO7M3EnDUyIc+n47eaIc+kRwYw8fZ71SJW3Lr4r+G8qDI6rix7qaLk/UVpRP" +
                    "Yy8iKfDMMXmRoJLHD6L2E+rz/1wOcB8KmDEigljTwqWjdblY3t4biWYk7rRCZ4uSVNUHl4yRcUqF1385MM3kfST+TZICQCSl+golKT+ZD8saO+" +
                    "z0JAC10IFFy788o+zm3cyXKTi3iufAdbJGs5Hp2h/TxbF8pYfWUhuqt8OBNtBkQeQxAoIl/NAEtND3/0vRv4XFcsDdoycuQMLBfIV0cySj0ody" +
                    "jPx84mwbBhkMbg50vKuqB4cd4gjvu+UvnZurH+8cI1/XIJY5p8xkT2u7YfEdvqS3CYWCeIhkJ0fhqwWYpGbFT5w/UJL3b/3NKA0aBI8gywF5m5" +
                    "toHU7IgZmn9NT03QAgZH+T4UFBX0uk75iJ/gBnPu/LGjx661opiuZrMf5fzOKOVdxHbfs9s6mGdw+qvoDICzjurbOK165IZbCtXskGxLOs+Ymm" +
                    "kgqJAvG4BvkX6IGGeFBEb2amilEepu3sey9nuYzvmS8Pmoddfz6OQMoxUlq+/es+gH5cJwy+GqJMGH9spyUySgF5g0qdEQ42/tkvUH33pvVjXu" +
                    "jPZPkivUlEzxbdrh3maQl6MiehmdBFqYQzBzy2KawEPx1bcR+KccH4PZCr1tBKtK1QMuwTu//gASAbeof6+q5Pcq9xzpTOfu/LAum02RFONjyq" +
                    "oAXEhLlEr3KlE504S6P8vX4S5sV0inqD1M/mVA3qV+10sbe7Tg8QqKeIBA49Jdowkr5NhfP1H3rHIodjd/u/LGCijZqeoPmlt8LTzSXBJL5RuO" +
                    "ArJZPCsCgXRjs6poTewFw+q8lPYWE/B+WNxCv+LBXRqeDaGDjSBnKLmJBlVLbE5Hoy1ePYT5Nik/UYLLz5btLizgN5kvQ6Es/yjm6L8YIDDpsX" +
                    "gfGbDnzhQAP/kgH1S/Qf6/4jM1W6pW9+78sB/JqsuS8RylNuwPRwIUXqgMmqx9R3zkeuDSTXO8Xw31PgmVqMVbZdNuP5fl+aeRfDqYqKEcLtIb" +
                    "6Thq6EC4mdfAAutSIkzOKZOaJOXN7Ktnrko+p4j6FPiGsgvZsoFXbCs7C5HxFRYM7RSebMRMKL1q1K7GDSw7+D2RUQNY0IClqlQwPOHW2GqQvA" +
                    "dOAb9ABxElpVELNMf9OchbPLm3Au3MrwmReiS7hAs98/q41C48WOT0H05YN2Ngvb/8BxIBt6h9Aqgfqu6RWsxhje78sC6TJSSka33lZM4mLLq5" +
                    "mPlgFsPMAGCKz0yBZqY3Q+Ic3UfW/R5PQesLg/5is5oB6lBd/jEtAAbJXJCbE4DyCIqjdUDfMDl47o+u315HWyehTf828tjxqb8zOm2l7hn7Je" +
                    "NoGlycnpyAfBOCETcKqpmhySqhNnyMIUe5eTyeSHKVYw1P/m7mJdPSEH6yaHJf33RXoVNulAktyiMKhMEqTvcSCdOy4zgj82ZAso6ApR7H4/8A" +
                    "cSAfShVAq/pCJFEXfORj7vywH8mqy5LyxfxUIqWDivxpnJZwc2U2NUOMganAX1NhQjoqjCkOgqoIzd5slpy5/UFg+ny53+pkYxExZezmUlG9u6" +
                    "OjwZQgc5um2w5JuCKCD6d6E5SPS8KZrLJhX6yxskMzxheMiD/Llm7RygGJykOHL3rHrG6GCgzUxET2u7YfEdvqcme0TftuehkJKH1+l5HNihoC" +
                    "ps4fqMjJIklxwg8Ai9uJVo3cCVJMyLE+zq0L8CB9622kAB/43//qH+oL5Iq5jNdudSnu/LTrbsb1IBosxMGhMsXTmNQXLmEWwy7J4dMzJVWD8n" +
                    "WfLD2htzisQ1K3ZwjQ7HEYuisT1PlGW0Cz73xtcrF272Y7pIlHFAinmnqKfSBghJ/ugMVsYLOkjpAknLmMjkz1zrNu9zz+JE0A0dhZBqEh4cax" +
                    "ZxZyvtATxjaQjFZPciCF+X8O1qJ1DSEd7OJdwfOYitEjTyYSwFWV9BktplXBuHqg24+fH0gH7Le/AZu1q2AGpKLRV3u6009BVWsZZ+q3b1KYBh" +
                    "7eDsmvYrj6RR5OGQFjfraC6BfndGvvKsurCT4KJjwgclo8Ei4l1S2knZpDd9wFNWB22Djlv5TmUSj/zJy6/GjtwSf6SLBm5gCY/rpoxsnJKSzM" +
                    "vrCxZV1bb+T+TbakoKABaA36VSyc57bd";
            case "alac" ->
                    "eNqVV39UWtcdf/xQgQElUjaGmD0SRCVpquZpSVm7p6DhUXCBAE203VHADKgYQDRrt50JUhI80YO/EkNNj5oX5UfsYkoTY3o27Yiauu0w0J7UH5" +
                    "nVuC1ptOds3Vn7z9zT9IfddnbO7jn33O+993s/997v532/3/sAAOCecL5qU0LFIADgga3WXH/SitUCAABIJxw1NQBA+chqrHZiCgD+CSrOAvxg" +
                    "853NVzf/BJQ9QcIBj8sTn6U8/U+cYrPp6PMHV55nkBh5sMsNMMDxPIAdI/LYnoKLpLurVLfjcKlBA6UaazrUxCVpsHHRoPzk43uzJSUMYYeulx" +
                    "i7XWEkLhPtD6RZuKXRRObVrJwwaIkmjeYFqT4H3ijMFb97bTQwrCmp+kMBCTALvG++w3B3cINeXlQ49jQIUWWmqla03woSZ4G3S+r+ARx/BSLn" +
                    "pWtmS+w8VOC9XGnvbw+3RIXwdF75oN+T4EYMYJLpiIYYVS0MIcy2SDfiyxqkiTVuNBmreCiN4ViXkvu0jHyqjZDNYYvt/fOG2PMyWlnn2Hnksg" +
                    "oKC3rOHc9wo+6BaN+j4EaH/R3O8LMrhvQaGA0gUwoYoP1OughwOFCEDaT+Wd9PuElpgwSumxm+NvG8SeO6GyorKuW+fjjj/tODvfYigetW8FGa" +
                    "QVYqfSue3gvo2QJoZSboJecBbw+9aBwu6+zOkHAUxrfytOEDl8qByQj3JiOR2jfHYFUyzeOaN1234ZYTH1c0BZhjPhaD2vYMD9nTock2T7Vpns" +
                    "wlD6hN+vo9ZZX4KxRVs+9o3SftBGSqVp9RMt0TmEF7wmozdPHJS1GPSGy7s9cOLXKJH8Oz5vlUz99x6gsn5W9dsYoSKMO6q59OhTUdyzTw2/xr" +
                    "ZF8Laj9jnnSJVrU+vkuU2O0KyQnwnHlQFA/tQgvIixa1w9i9aInOM1OzzpRDAWRw6eW+4iNCtYLJsQ9+AoqozUhfmXS4UoUSFOWsPfoZQ7fjMg" +
                    "/C56KueaRfKJEPj8ank2kpMkUzcekhqNwz2LgAJpXatS5PUVYJF+35683XYNd4sBPY1zQdnx4v5VN56gpkmzaarocmiieRcQv1J+4YTY5MlGnD" +
                    "vnZf/+VzEjBQrKo40qg5JRcttuou2gsRgYaCoKIHZyykNI5vdFVSanJcqXoE2O8G4Y3iKQ4T1p5lcgXFFL+Y4mttB0sPZ8xm8VflfQLvxqUmEe" +
                    "TRyNdxueOpPI/lrSuPaVMmI9d0/uxcgRfquf1SVUQjh5eXb2tIMuJ3+UVNEYVyokCTlW3xeH/7s7WJ/OxXQ5TrhL2tMTWc0AhHhrpG8Ep+Qqk9" +
                    "W66Naq9yy9WSEvmFM0352ZkjaFGveYgT0evt/TRev/yqLJiwRWwRTh0d39BngdahhdnZ3Z7wjwXCkejqC4yVfvKQoVVER88DZiCU5VcotHvPSs" +
                    "oh8sL1SDoiHhGtVXI0/hhNFzC0Uto5EoYn0NV7IctRWFEVMEinQVt/dkbdBjmFr0G9AmhVeQ82sf1Uwl/lM3a6mKO3hXZVzFRd+8BEjJOPIvwV" +
                    "ZonG2/1yTjw2V6rbcrZMJciUndIVFQ961yzqdJTioXgOfu+1BCnpUgkI2nGj3ohebAWrWtRkvP29gVReGOP3g7CgpQ0SK5xucbMK3bicMHKMen" +
                    "2I2xgiyIT7kOyaw87O1uk582gA7RQSEDQF4gVBroXQBkG608JJ+nhaZouHfp9rn2pgaMPI4EdCEuZwGkWtmxgW3evPzeak4lOXe+0o5BF1z8M9" +
                    "8q4A2xt++P6YT51eJaIlQ2QdZgR2zzRVO0l5R4r2+PPDQwekdLqG0zDYkmTax7W7PR/B3oVfWWh0+gNSsJcdaY3d5aGQWPlcIkaGOtvm9XrH8M" +
                    "DwmQjXkn+IdXAXoMf9hcPiryqhuUpAyyEmcikaYktrNuUGZWRg2OcbTaqGTLW3XzRpWYr704mGIc4xF80HPbKZceoXmhKApmNmOqDXR4Knc3AL" +
                    "N/R6BCyHTveZ1RJiYYW5Gz2E3F0/2sGoSXzrTmb3q05PkiMoxgwTsM5G8bm8KTDOqoOW7qfhWiCINH35XCLTh8Y+0sU/qEw59zYsaCrNB20ShC" +
                    "bAmLutK1TpXpcpEHduBVLmXzv2YWqA8kFFn29EyBDKKIWn0qrWWSSo8Ief/WITBBgAwIa3s4MbyN0c2vzp5iNA/lV2wG0SpH/+cLK608j254BA" +
                    "HngEBoA8bAmBCtKPgyQL7MEgODwCUe4CaOJYwKIxpZvVpPVsJrJUgd4P4TSttP3Rs9Gg5BREsp85HonEBKKlkeiWu/qgh28OIIBoNmOsKbzQ3E" +
                    "bLA4RiR0jsK/HTqcxhoey73EiEUmLLl2ekt5WqJj9vGEbXTAb4jdcMTWKOjKteB3rkEiZHS0ws4tTpfcDBoY7ndJeatwLtWfOJMG9yTV4OxHvI" +
                    "87DXE6jDz4RSe7mGOKlfcPB7h0yS5jHk2ZFB0SeZ11Bl7MjFod2HkBy13NMTB66BwzSWocsf33Oh+T5jeXXEneGOVAVmrFAYIsfUKjGy5xIyVY" +
                    "Ed8Xoc+5YGh90c60MLHu39/dKPKusoV1CSlewZMjCeiczNA/bfpB8nLnL1w5pmzGffP/s7NyyHn4BnL7NkLe8mFfZ+9V5VoQUj3lEnXy+sCDZV" +
                    "ZUXjUIifrENxKXAIoUpsWucvmUoCmDDJ7mSdR+hAElfkWRwmdL8C8fKIRxmf+gX+EP542k8wvy3VzR3OeR1f92kHMtEpajmPaEfeT4y3+FsYa7" +
                    "zusdnoriFTxOw2jCnehVcZNJD7etbuYZKNi0QFIiDZSoOaCpoz+cdcoZJTulChsHjS6z1XUSZVb7PXMfiSY+Cs/Va16iv2ZIyEvWijT3h9eVUi" +
                    "aROpGzcw+nhYuFU+uxJ0tb8/dhUVutqP3O1yCNuemZb7tQYOceG6aHk5wVg5byK+p2b3JAzK/WNulB+DvA/ut81wucF5dsLwQqGK/kwuoQkS4A" +
                    "kvxdehxZRzixwb6zAqCiLN9QGZkrzEv3gsyT3V9qFasAxdHHxTHevKzSpRaq+mtH/rwIBwKrcMaDlBtNPpYjZvVHhdtP96XNhKsrPSu/W7VOjq" +
                    "MG80vCBRKCcD10nmG6EgxSe/EsQUr/l5UUH6xbPwvV++qMdNUREjEzcpTDKZ8GxIdeVuR3Eu8BxB3aUFw39TavaWoGUDllnwDnLn5j5feE4KTD" +
                    "w1lCs60qkca8dnjMYesb3L4QdlgUgqP2Ha1b5PtyxKqfyKPFc+lic9KxKZxrW/+V2JvO8Nd3CNMWRePtJBPKQr6gTS9snbWTxamVzGOvB3WyPU" +
                    "iQwK6A8KoIIizwSBvLZ3bQJyTfesGJq+zJNOd4wu2HrehNR0CIKuJQoSqHfDxFPdYuWsWvLfi6ZILxzJFfCT4VN2OLXTd6MBp72Rtk/C3KJuTe" +
                    "3gn+aalNDHLWjqYeHD2+eELl/+jT/+fNFrFnxO9hXqigTurjQOcQqXkEvX5cRH+ioBlJgJCsQ9/kTGUAcl6jqkiuajk+csfZHyKJBIDVw5dRlo" +
                    "pJXRxo9bJwZPsz+lz5Hzez4PehLlf1OzahX2e+Ia2Rtn+sXl33f0daCd0tu68W9L949hQRf1up5cuLXBF0ECiDahnd2rtQ5mc2zo1iunQ0hfbD" +
                    "j5WdUm9qy9ZT15shGLVLXWRpMR+Ea51YS9kUEAC2W4ncO4b2r9ex8G/mfBYwvWnI7qVzD5Jecr23sS/hONC+4cw/3/+/JrjM76LaCa2nrn1yu+" +
                    "wH0M+LLVaK7GBNBq/O9318a2O0+ZjLWOL2fqTzbU7dQ8ivWNsuo6Y23Nlg6OZDXXncAERr31G6B84+NxrtFRc2LHFagNjlrwC/m39U59LdYeq3" +
                    "fWG3foyKprqw3/cW38VvJ5fNQt/K91sGFGDoWO3zYLEfiO7UstsN65bZQviNjCYnyBSd06Axfbd+c+uK/1tude+yaRqRwAIP4ak5+sdxpO7li3" +
                    "H6v6BqNzy7gV1prtdqvwdhoSM76j2mar3WnMp8yP2coKOU9uAWZif1/VX15aUd14oujggfyiA/l5ef8COsPGKQ==";
            case "aiff" ->
                    "eNrt2elb1eX2BvA9TySamoWKZIMjjkfMAQfMHEHLKRVPlqiYdirRUlOS0JRKtE6aqFh5xNlKwalMHHDE44g5NBiikpYjsefp3Pd37Te//+D34u" +
                    "H6vODy2tf2y97rWc9a191n8JAUnW7jvaT+ffo8PzgFv+tq6ww6XcMndU/0jN6bpdN+hg4d1BuvOq77vz/rW4I++WV6eAoMy54FY8JGunAQTG+t" +
                    "BrPlPq3cA5ZmV6jQAtaERVS4A2wtEmjFPbDryyltOv2YC44azWl4f1raiU6OJc/XENXATx1/pr7nafBKSplNSe9QfA+q8RQ4blygomE0axQlzA" +
                    "D77dO0tJo6DQFb2W2a9CZYq2fQe5fB4vuFpmeD+XYCjewJppJD1OIlMOaOAMPdHBpQCfoNi8nwBOhSn4PwjhEU3RJC6WEIlvxNjV+EwAfZ4L+R" +
                    "QwPGgW9na3qmC3jzOtGjV8Hz6Qyq9zS4/xNPCQFwnb1K03Io1gPOszcp10gvfUlxr0O1ayJdXEr7q6moM22bQLtK6JiFymeD03iR2o6jiXFUMI" +
                    "r+OgyurltoaU2q7gzuMS3pZBvwJL1AxW+CN6k7ndgNvpcv0593wJ/dHQINx9MPJyA4Rk+htRBaOwHCA/rQg6mgWz4E9N0yqaIHGBYMBGPT+XTk" +
                    "FpjGtSLvXDDn9gNLw0a0LhmsLRJpw3iwNbpBn1rJmwb20R7acQQclsdp0B1a0puOvkHV4yAqpil12Et9JtGgmyTV2Ot3av0K1doCjtu1aU8/mq" +
                    "unxI/A/qCcVr9NSfPA9lsDyhgO1vBxWtCMLNfA8kEimJ33aeJ4MJ1rR53eAWN+WzAEXqPR00G/K51qPQTd5CMQLmlLsSYIzVgMwfP/pbYtILBk" +
                    "PfirttLIBeA79CW1rQZvQRXFtgZPfiY9WQzurceoRytwXRlNswfTswfAeclN/95KIz6jpzdCtbcLXS6iQ3Vox1qKVGM0Hcmnq2PAqUuh+Gfo1R" +
                    "m0OkA3CsH1jxv0yb/pLxu4h7xAB/uDp+NmKpwE3oTO9GMQfAMb0W+Z4J82AQLRPWnLfQgmT6MHcyG0dBGEu+yma3mgW1gC+tYFVDYBDDMywBgz" +
                    "lnYfBtMwO92pBea5V8BSczyt6AvWRh0p/wTY6obowzfoQXuwD5lDW0MUNoGj3xL6KJ8OtaYHVyCq3lBqn08vFJFU46C21Ls/tc2kOnPBcfdF2t" +
                    "eV5m+iXpVgd1fRuudogA5sf+hpTldypIH1s5lUuxIsuS4ybAbztDgw/T6CXrgBxs3DyLEADK+ngP5IEsWVgm7WVxA+X0wtr0JoQToEr62nHkYI" +
                    "fJlFunPgn7gZfGdrUPc08G7fSs07gGfDKYqvCe5d31L/uuCqWETzTlL8eXBeHU55wyh1PjWthOpACf2SQyWZtHMkSTXK7/Lv8hrt9c6m7UjeR9" +
                    "5Te3/5v1zz6pH2DO5+Y0l7NnlOz4YnQJ7fu80Nvm7fkvY3+ic4IaBrSKtPQrD7ICp/CKEPC0E+N/kMdTP/BH3cY3R4ERgm5YF8/sZNX4F8L6ar" +
                    "a8CcMRnku7PkJoG1djv6tATku7bNXkBaDUg92AuySasTR692NK+apJburAKpsUi9Se1JHUpNSn1KrWp1KzUcqeePXyKtzqXmI/WvnQU5F7YFjU" +
                    "k7L3J25BxZVgSoVgyYsxaD6c4R0s6gcc8s0s6mnFP9hfrUZhTocmZCuCKFur4BoWVJIGc/mNIMAlsLKNoJ/un1QfqGb2AZePcVUsI98BTF0nNj" +
                    "QfqP9CLXX/toUV/qsAKcN9Ppy+0kPU36m9brpO9FeqD0Q6nGonskPVP6p9ZLnU9VkfRY6beXeoHr2SiSnqz1Z+nV7q21QXq4Z9VxkN7uLegG0v" +
                    "N9B2+D3AX+h24ILH4IwTbv0PnHIfTuFQg3HEza/aKbXAv0tRJpZyEYRu0n/1YwrpoNpucO0Nk5YJ7wHWn3miVrIVgt8SR3n3YP2qZ+Tdr9aO95" +
                    "mvL3knaHOrpeoPeTSe5Z7c6NqukiuYvlXpY7WqqxbyzJPR7zKzicj5Pc9XLvD15D2jwgs4HMCTIzROaHuBVg3RhD2oxhWa+nhmVgzg2CydeBtP" +
                    "nEeHQ1NesMhoU60FdUU/f2oMuzQvhhE9Lmn1BBfQiGR9KYXSDzUiA2BmSOkpnKNzIXvKVvUNLf4Nn/NCX5wH3yBo0pB5nZIvObNsvJXOdcF00y" +
                    "78nsp82B1dfaksyHMitGqlGbIWWelNnSuR2cjXaQzJ8yi2pzqSv2ecooo7PNwZ3Ql7SZVuZbz5JD4H20OS2fBzIPy2wsc7L/ehkEsk5B8MlVVN" +
                    "IdQukDIFzjXSpaA7rR2aDXp9H6KyCzusztxkVrwNQ8n0pqgMz55ltZIPO/xdcEZC+QHcGW/j1pu4PsEZGd4nYMyK7hmFlAsoNo+4jsJpE9RXYW" +
                    "qUbZZWSvkR2n4TaQ3SeyBy29S7IfabuS7E2RHcqwDGS3kj3LWvQ6dUwCS+Fe0vYy2dHM1gIwvT0EjD+9Sx3/CYZlP4O+KptSHgHd+nLSfsKjLk" +
                    "CoaCNFt4fg5J8hUPoytewO/k/Hgc9biyb0Be+lJpTiBU/pNEoZCO6fSmnio+AKxVH+J/R8BjirLtLmXTTlGHX4mqxjofqGiU48S3takVRj4Xwq" +
                    "/pjKDlDVb+Bs0JKSt9CC9VSaA6567WhKFB37Btzx9Wj5Z+CJyqEPm4LX/Ax9kgm+eqW0YT/4E7vRT99CIGMTBGvPoJ0xEBo5lYJ/QLjgOOgGbq" +
                    "KHF0CfpwND4iN09Tswvp8JpgYzaOdqMA84R78cBUv6MbrXGKxv5dDtX8H2zzIqNYK9TWNaNIMqrOBoU58yAvTta1T+BUTZV1DzKdQzipKPklRj" +
                    "/27UyUtx88ERaE5nVtOKckpdT3W7gv3wMJrygKKOgm3tBepwFqzFP1DSZ2ApPkgd+4F5Y1N6rA6YMg+C8foqeqEjGNaGSD8G9GNWgm7n91RzMY" +
                    "QnfQOhQ9kUewCCs3ZA4PJs6vI4+FefJ0sW+DJ2g/f6NRpdBJ6fSmjkKnBfa0BveciSD651VhrYBZzeW7S9Dr29lzp/TlGrobryMp3sTd8vp+31" +
                    "Sapx35N0bg3dHwbOenep7x7KClHJu+CqGUfjllDxHHA/dYNyN4NH14lmXSJvI/C+XwI+Ry1asQr8rU/TsY8hkHYGgsYvad0nEOqfR/ejIbysC+" +
                    "gSz1CFH/Q5U8HQ6h905icwvj0KTDWG0oZZYO72Gp2JAktqDao4B9a0bPo9CWxDO9GB82Bvso7mb6Ofe4CjmY3+NYw2vUm/joQoyxFqmkzd7tDA" +
                    "MRSpxirqkkaN14IjXERlv9BXOTTOSA1SwP7fBjT9X1TXDbZvfqDuu8B6YjilLAdL6VF63gDmHdfoqWwwLVoJxof7aVgtMBQtoppZoJ8yDXRH+l" +
                    "GjdAjPTITQuVSKXwnBnFwIVH5N/bqCf4uL6twHX1YT8D5YRRP3gOfaCUrrCO6/9PTe51TzFLi+mUdD9aTzg3P3VHo3jbqdo5pNoPr2VjqVRj8k" +
                    "k1Tj9jO0dxedyaY7WeCs/R31OkyzR9C+GHDZatDo47RLB+4nQjTvMDnzwfPmQrobA94MOwWHg29ROfif3EQ/NIDA8C3knAXBLwoh1KWYyhMgvO" +
                    "BV0LVKoguNQT8rDwyNRtGBABhfm0vh1mBaMRzMbaroYD5YBg2liyfBOrI9lT0AW/8htOsO2GOn0ZwOVNYKHI03U/oWKphNl2MgyphIz8ykxGwa" +
                    "sIGkGuV3+Xd5jfZ6x+XTJO8j76m9v/xf9jm3SXsG2+41pD2bPKd15B8gz28ZbAPzoZdI+xtNKx1gDN+gcR3AcHA7xUWD/r0UkM9NPsPwwscgVP" +
                    "4ndZ0GweXpIJ9/YMSrIN+Lv/Er4MtdBvLdeTOKwXP3NL2VCPJdu+fPIq0GpB5cqZmk1Ylz32maE0VSS3XGg9RYpN6k9qQOpSalPqVWtbqVGo7U" +
                    "8zvfklbnUvOR+tfOgpwL96yrpJ0XOTtyjrwTjXS/Enxzp4K/ThfSzmCg7wLSzqac01DLm3R2PYRnLARdbCEd/hz0k4tBzr6h8BIYh6bSQweYPr" +
                    "kJ0jfMO+LB0iuFSh8Fa3IFHf8apP9IL7LX7UXT9tDJieCon0evDSLpadLftF4nfS/SA6UfSjUmP0rSM6V/ar3U8VsNkh4r/bbZPrD/7CTpyVp/" +
                    "ll5tG3oXpIdbxz8H0tstqYdAer65ez2Qu8AUbQPj1GgwnP2YWt0C/UdNQHd9G2n3S3jZfQjdL6EBKRBc35NMQyEwfj74j/egNvPAt/JF0u4179" +
                    "yZ4PGWkdx92j3oXjyWtPvRtb8dpfUm7Q51Hm5JHxSR3LPanVv9wE5yF8u9LHe0VOOeCpJ7vPJpcDpukdz1cu9ve4W0eUBmA5kTZGaIzA/lE8Hz" +
                    "ciVpM4Z3VIiux4MvwwB+80nS5pNA53F06SgEZ4YhFBtFB09BON0DuugrpM0/+tSbYNBtoLX9QeYlY0UlyBwlM5V5QwZYEj6n4kfA2vNXKjaDrU" +
                    "MDWhsHMrNF5jdtlpO5zjH6Icm8J7OfNgdGNTpDMh/KrBipRm2GlHlSZkvHIHBcG0gyf8osqs2l9oofKTee2lwEW+ke0mZamW+tb3cDy72LNGkO" +
                    "yDwss7HMyaaG8WCc2x4Mv4+nxIOgz9sJuqqPKPkVCK/LhFAon0Y1AZnVZW4PTHsF/BfTKLEKZM73PT4XZP73mq+A7AWyI7jz+pC2O8geEdkp6l" +
                    "WC7BrOhakkO4i2j8huEtlTZGeRapRdRvYa2XGuDwbZfSJ70JTaJPuRtivJ3hTZoYKTQXYr2bM8yV/QiWLwpvQmbS+THc3nSQX/km8g0OIjOvEf" +
                    "CE5+FkI1MqnwbwiPigOVD6p8UOWDKh9U+aDKB1U+qPJBlQ+qfFDlgyofVPmgygdVPqjyQZUPqnxQ5YMqH1T5oMoHVT6o8kGVD6p8UOWDKh9U+a" +
                    "DKB1U+qPJBlQ+qfFDlgyofVPmgygdVPqjyQZUPqnxQ5YMqH1T5oMoHVT6o8kGVD6p8UOWDKh9U+aDKB1U+qPJBlQ+qfFDlgyofVPmgygdVPqjy" +
                    "QZUPqnxQ5YMqH1T5oMoHVT6o8kGVD6p8UOWDKh9U+aDKB1U+qPJBlQ+qfFDlgyofVPmgygdVPqjyQZUPqnxQ5YMqH1T5oMoHVT6o8kGVD6p8UO" +
                    "WDKh9U+aDKB1U+qPJBlQ+qfFDlgyofVPmgygdVPqjyQZUPqnxQ5YMqH1T5oMoHVT6o8kGVD6p8UOWDKh9U+aDKB1U+qPJBlQ+qfFDlgyofVPmg" +
                    "ygdVPqjyQZUPqnxQ5YP/f/LB/wFbe9+j";
            case "wma" ->
                    "eNozUNtU2pd2XnDZTYZVDEnnct4xMoABKxAzMi28s7rHfeV5wb4nDAd4FIJTMxiwgwV8UEaD3dV7G+cyQg1hKJD9DGZuEJcC82V4IOJMIKU8EM" +
                    "zwi4FhK/P+eD2QPY8h9uhB9Qteurx6F0j8GUScDSzqsOTCJfbHlwSnf2BYcDJuRUAKVDUjgwZDOIMvgz6DK0MeQzJDPkMKQyaQlc4QzJDKUAKE" +
                    "EF4xxC0MPgyJDGUMaQxmDMYMegyGQBpEGgAhA8NE9jvbtyPZXAS1w2Fe5g/f6POCK/4yNMTHuGgHnD28vz/xvGD3JmD4bXmkAAsRGSDmALsJBB" +
                    "IZmRgadgN1yzMwhDIKMHBB3QsmQ4EQyHQIutgma3hBcPESoK+YPb7BfOWIJs4IDj9xoE9BvkkB+rIc6CMFoL9Twf5NBLIdGUrB7HwgO4zBAhrm" +
                    "iYxmaLG9iQd7fMLij5GxCRgJsbfA4d7A0AKJWI5QRkhUhjJ+tL/AwMD8FygoX37zW+19EPElVtjlYqmtd9CVO7MnlZybd+u2n+ihO38/mawOjH" +
                    "v3K11LXrvo9dKXpx78mr8vL4FhX14Aw8vKPSbTdLYsLFr6xS+oZrr9tmmcJ1/lb3578MELE/7w9eKic+dn6/19Wbbc71X4/dLr97s3z0m2PKZu" +
                    "2DkDCieyuUgyTODEgqaEgEF1pt/QAYxM8FB2B4fyp/+wUNadwuTil7fpwpfupSc/lzqwHeCslWfu+nNEiFE/V8FvFJASyszwUC6CpGV4KMseWq" +
                    "ggKTH749G05++CrtT3z17HINUf90v9xOPHRy9PZGlY8e3SaPgRGcos8FCeg5aWrYOWek3VClkJodbter3u6PUkQ6abv6TtzG1qP5czMJ4Ku+k1" +
                    "rdkx9dap01v1rqyM9Xq9btebZIljhoJAADKXm1llkicITd82YSSHMsMoGAWjYBSMglEwrAAAMKNRQw==";
            case "multitrack" ->
                    "eNqtWX10G9WVH8kfkcayLMmKsYKbesRopAkmKBofsKih8kiwGmUsT+WExlsKVpBlp7BFKwc2ZlPwJDMjuaStmJkEjRGJBitO6QcbwmkLpRBXTl" +
                    "uNURw3tAXa0sZgK6VLWZO0kEBIdmygLO05u2fP6Rzpj/fm6d377sfv/u7Tmht/PzGBc7QGf0f9nqGr8bO0Dt+V/pfo9uRdw3dE8ZQ6xdLa5t7R" +
                    "QQ2w/Njy5u6Ht08dZabeORDsfuaB3m8zq4nH4r3foeUPRk2bHr9DHb3V/cwedWTZND3Y+51d2rs/GLb0PqO+3GVj3vhgN6AX+Mc/y/o8f5T5me" +
                    "G4tO7FJ3Y34L7u0a+R0Xvi12DrN1yzfoPb/fmuT46HC5n37/+CiX07/cuvMGtuyE1d7guMpX3bPthv+URd9x1lfos9cfrxD/Xe+SKtGf5J+tHN" +
                    "330ByZn+lKMB+/dyu+/+cixNAxx382033XTzht205orXdjM1f5kHTmUO06Yf0qZxWrv5MLP5aVqz+U0a+MgIWgC4/dFv3Xwl8/NczdVdnf3I67" +
                    "dce9cXL7/noUeuU461+tc+cGBz6+euDp+Thz7z8GWNO6s+UiL5Iq1Vlfj+dv7tN/Muy98o0XXbTWSXfzetXVZCcyO7ef7BA7Tme2nf6cTKr7fG" +
                    "aNOyFqroOBn1jwKAvRqoVl+UAM2uuqeAJQBwjHZdfW5r/9Zo/Nk3ftT6pd+sHv5I9F0v0lWq6DeP3nrvSOKVOz8W/TdytcceWfxI7i/To/+r3C" +
                    "Kg+W7N1lZVrvm6xV8nmdfP7m48UdpUbLQasstx1LX+KLNkfM84PJy/fWp0sDR+40TqxrC/J3BjJJD6G4cO/+L2qQdu/9hBg6UD/3M1r66+XV3t" +
                    "aV9e3RqP37NhsCTfOJEObI50bSJ6woGU4HZft/JZ73F/+AB/3fYjk/8f294Zvf3/te1H5vzHbPtp//fv3vzLo8xf3otZTtPAxAINAKMXVo8CJ5" +
                    "+7/9KU5vV19x947hIAmICC4QdvX/+7v59vvupTWK5F+Lv5V7+8iTMdvf7v5vHbvnzLRBe3SxVz6dy3dMCfSNWr/6n5lXaq6lD1V2vuqCA3z9xg" +
                    "fvEvW7zfiPcfGtU4kKamaDR8SzpQ4kR74gh1yCC6yxLSaMD0GJQRqhh7C6pL6ZgnD4QiM3vr2sZO+Cq9OtrkihLuHOKHYAHynYrp6CKXwlxmDH" +
                    "25e91t65pp09g8KeW3xGPT7mmImconspSgdxHH6+hqXZEXD+5PnT9Yt0EreO01oOlI1I9Y3SUyo7hdfvdCOYew4nhTkCVMZ58X8IUHteUFmeet" +
                    "SMi04AlpfIVsFI+5dlCHZnZWzl5k555se7Aq5Qo1ukI2t6Cls1bOahCdoA0tirMjA+hDWHeJz0eoOdTV1IgQENdMQoyLRGpJ3oxERfUsBxva9m" +
                    "072m4PZmkLVUAxd9qWjTGq3m6ehIJmLc1TszG7RcpNRsb7TGNtcnI+GXMRJh3tSkwbEAIkYMngImlTJaxCNZ5kvbj48tw1+Ymuh3evOGJSB1wW" +
                    "BoCqc6sOg2/W72zAGt5u+GHlyp/84FHDsWtWTS2ZNT3PbRi1dURJQ24RTBQTqrqo6RHQRk8pgpzesiNkIV2rrwvtwDN82LpX69lC8X4IZ/eXCw" +
                    "6DrnUYTDJ+3wJnyczP21w4b9ZSyiRDZKO+ac92BxQidbzVod13eacrWW0yWrKRcWt2UBuxcvALosusB1vW1bTZm2Kuar+e4KFv9uE0b64SrjNj" +
                    "LFJjUHxHthVEqLqKJu0jvgJqUJx1iJQN+Q2uCHRFR4QOWmE+vvUyh0MXsdD+BZzLOapAW+cLmG/ePR9eXc+FrDoaCa3iZnBdtBRl68HxagIntR" +
                    "B8wl4LTVII4cfnI6EW1sDTeOttN1TjLUhjyrdrx0ZtxJJy8LDsojpByyNayFShp+Y8VvlkhAWDEeK4FroCDPmh7BbroZydcKuuoIfMGNRvZZTo" +
                    "4cfyZqhcZ2x5ugjBWas0N/e0iIE1xsFrxJnaTMH16NhEV2wXsHolTTR/JMHt+md1F3XXrvpS7d6aH1Wgs69p/qvh96V/7UmMfh2oE3Oz/swhfa" +
                    "ZwKtJiEOBi2A8voZizblo+Xo/sN6OVXp7l1QPECU+CJ2A+GAlSKWYONQ6kivLZklhKPV8sTCYWV1LLLSyn1nhjayRazNm7vZV43ySXO/Tna1EW" +
                    "tBZ5KuU0MHy4OaFGfDPplXxP79uIdX92XZAFRw44jOGYgnlsbtmdjUkOeEE+FWjzkrNhNZ+89svGg5w1hdgUMJiQIIkOEikkdnIo9qSfhwpjpx" +
                    "9C9hOmSpLiI7gABZM2R5VBcCtFZh4/gJ12XPrz9SDfp+MEt8hTLCwhtTaaQCwh07yEumcGO59cf8vxa/CyXxQ9lNcgIfGoxwb6ERvMW5nsAGj1" +
                    "pFhC6T6PEtvbDopSNgCXnfrRV45OdPXsAq5cMbP2teDSVUs3L+1ZmnurtfLcN199+Njpu1/WUMD+ASgf4Qh+mHUiiXJZ4tkUdqJeYOE09ZAkdb" +
                    "L8ICTmPMRxg4zqRUX0CHyElL3GnVJ9SikKfiQlSU5IOdUndexEUsgaPJI4qC/Pkgsoyfd6BcGLxNoyhRwkzwaHeurhSrDXA3W2ZV6KJx5xn7mv" +
                    "p1PwJIc2gkRHLwsXBAHzbHMlBxBC7sRAK7ov1O30hMPNQySK6XPl/IhDkDA+vgaH2M4zMVkpi+XZQdAieEg+kxM7wSYLWV4YbyyPR5UeASLbro" +
                    "nmitnkjhFWIPjwNj3hdBjxHAuXTyACH0AGHfA+XBC5aZ5rIss5ReZ2Ykhy2CnJ160NZQrKySYiOILJEh0TncFQfCPsyawmJ7qe2A2sX4EVjSn8" +
                    "Z/idq949dvFx7Xdraf2bhmcqbb1PLH2681ODrxyYpovPXhitRq8zC/MuS3+E/YxWrxOQajzasz4ec7Sa9VK05bxhCmXk4C2H5+w1VW7XiOVea4" +
                    "nKmw3zpoPBgEJn7LXEcSZOq2Dnm7WiF+o6EtJ4DWGyDyXkjmrfQbgbnqkxuKhFHXshG+VsLjMrKlw/lQ+4x1CxmEmvQqxtqG+UmWyO6Ul7U+++" +
                    "YMSXjkZ7FjGwlpSKutCQ1QGVqNyRQFHy0F4Wbm3dhoIxxicWoSFTcKum36zlEmci05xtNGBQFkx767SUW9YxB7VGc31rQNsaqALjxbTZkKdcBL" +
                    "iKL2XG3JKWt7LusRHGZ7cEI2suh/FeWDBgD7bDhOCedqfNDg9CyVCQWnj+tEeFJ/fslhtBGu8PIDjr2lpTZkGN312QbZQsg1Yt51/ftwi5Gh0q" +
                    "BGUI00EtbTLkLwObJUvIpmVkqc1uZn0Fd+YU9aBhbFGPBAOgtW1ursjIdcCR2EubDHfoJ7ru2QX4VrKgaoGsp+vfNQ4af9ZwecPWhlzFMf3871" +
                    "YfeFV323+48i/7tMMYKp2AiHwkQhXyfZ5YJd4isnqeI7ORuM2XNoPU0pEkdf44aBmvcYCh4eVyNYJxpODCp9JWA8/FSgGEyE3Gt/G0pc9/ok5g" +
                    "Delqk6MjHvVCBJzLUjkdR+ZAKyiIqId1jYhl+yqPICkxdBZnTlE5MD6g94KWHWp8dPSm8Nmm7k67tXOmKWTlWZBQMawjpki8ADa6c2CIbGsrTn" +
                    "sl02TAGA03hWzTonuSPBOP2yRPtxGHplmjWT+bjA846+s6enNyedKsbaVUlMvlUFjJyNFMOiFPIwNaZACuhIbL6yytAfuOXm++r0JiGJSzr9Kz" +
                    "sOIiFDxr8l1hEMtHtn9WENse1OYW0lTan3MNg80RctpDW+ItksDMKQKumI4EMKxOZQ6ZO50TXTt2AZtXDF/9Fvle/7v0+clzc+c073S8/W8VpG" +
                    "Hmj41V/5X4p7t6+r8/aphTyDGFSBTm7NZykSlRS7IfaY7NkhKGcaEaKZYeSjJjHmZyWFZjAJ5pkTt6HYhVzileFWxziBpoNY521F4jeBcxL2q3" +
                    "8CySGMMM+YCOdS90hEkvXKRk1GVlwVAy22X4w8hspNfDT6mh+6HPJMO0IRvLR6yih8w83RHwtJrwXnHmPuv+ZLRHl/IkXFQ7ErJ5aBEhy8XWYd" +
                    "Sb24fnFsY6Avus5ZmdojCVJpXMIcOUMs2IXh3tx1x9MuGiDqHh2GmvxOfsyy7Dhbqq7JY4mclT6h4g2SZiKVeUlBbKr0v8LKc6pESdCjg9sWw8" +
                    "Nj91eN4vuVpmoiZ7jaFwss9LnCmyKR3HGm3uGQ2ZKUie6DRmVL0GfuC1beIhtAjJxbumJrqk3cAXV3BKawsvrV5yvxU8S759+fmx92/VfLGy7g" +
                    "8/fLVhT1NH9YmnoG9ff2m0JufVZnEmXVM8CMZQPdHgQBU/ZwU1pmwkpaI+U2RBKr2jt76Ki3OB+nYYJ3wdicdYtJQoiuDIjHWemfdNsva1rCGf" +
                    "UGmTPF2MEKxWKRxi2mCBdfj27LPCmRIl294oMzJdbRzAcCg/YhC5/WaeQSGTw7NNtsSbKfe0k4iGSHvQ6gD7niymWk0+z0YWlQku4YBiY+2LYD" +
                    "WOp0B6o45FcNpUHIb9ctmNtgaMPTI5L2i5CPURZUqMLWozBfiTlInKR2r0mJQeQUE8I7oGXdRCkSpyg+3l+aLKCL55p4fKm3yCw6gyJigFseCa" +
                    "1e06Qe/WMbkzrVtbeNoTarmqDpZ5qydUo6NDg1PpcKPTSFdj+v6Nej+vWCjRtbZGv8yYCqKxls4cNPKiS0268JBZHxWgqWc2TrxEAzFg595R78" +
                    "VLlw7ceumGz14AgHrg3dJ3TWob8snJH1tGH1d7kE9O3n1z7ZtqA/LJyW+t/s7VE12Du4A7VhKu5m3y/H3vbnnv2gtr3gfef/3iLypXHPvVDbqj" +
                    "vxm99dP/3H8EAAnF77bXcGrF/MNOZaZFRDEewjMLRbVk9mGGmRokqkgd3Qap3etKrqS0X7HXSIrfAaOeSAtH+GQCA8PWTldvZdCLCVhHkuX77C" +
                    "1ePDKgJ6V5vxv1cKFG1hOJhgY94Xg8nkw2qTmqgqaao14p5XAsZ1OUyA6oVQT1FxXFf9rxIaySZfsyrEqYJ3PYrlo6TrIqqGKYl2ehmbXJtWFr" +
                    "iTaJlXhYZaK5bCQhl1VEbc/JgmtAX56MmiqRYW8b6y1FEoVTvZiLKEV6tBDmBBN/GOaEHh3b4Ek5QALnCExF+HA8VigySytw6mW5YVgm08Og2o" +
                    "7Ntshc7ASiqh7u1gcDHQm5CKmMBkNbN+xeYVajK5auvRQ03mtsqJfqLzOMVZ6F/njjs/8+tG01ACCkwocaM2UXNdmHto8nR6bb1SpCSpLvjD+z" +
                    "ICBEdpuOswqdCx8wmZ0oisFKwelo0Kb0SDzZiLdIqhN64HmVycR3rl/tTGX72nMCnxH5mEPlw6UIWe4cb+QDzpzI99V5o4fPcH0Y6uWpaXhewl" +
                    "QR8GyjvNDOIuHMka34wru2uTaeeV1PKopB8cI5Ae4UhMxhEV6YDF3eMBuPT+8b9i4d2aaGa9lp9GcUxdMLzbTMDmM6fvt4T+4sWq8VOxVvURA6" +
                    "4nGSp+bLZzMvd0SCrKMS78485q1EEkqn6GEKSJxwEXgLzw/oS8mRjjtvuLNtrpKSRCFzZEcTIaSKQ2Q2hEdIlKlElf3WQ3z2/E8muh7YDTywgl" +
                    "hVa8Nvf+Vs9q17lwJLdUs/PzP2lzUVdI1i/uNhT7726EXg/YsxoFHvLjVtP4i2axOG8l5toXAqXK349lwLpow2yD12va68hFgIB2Y3e1P6bJx6" +
                    "eQBWVMZM7dnPyEgtdWZqt658iuJs3kvFLTWIZcvqA6nRpEnOvCynqWm3h7D3mM5ReWsmSx2HWsQOKyzwxUnaJDNCFXoDG26BkFAtD3UbrdCUy5" +
                    "bZ8xyFGdwS4/8KEuVicv+m2WjWRmcjt0xuCegFcI1VrmanO0xHaoy3bsocqdEba++fHNDGBKPZMVc4LBmcbUxyoaz2TFC142QvbK/24I9o3cfa" +
                    "QSs870UPYW7QElvY5TfNie6SFaHsRURlGzraAb+01exRex+r03mFrnxyFZR56gtPm1wElJnbF+QvZIOxzCSPeTkb5Cs8nWRhtMpIXngB8sluhc" +
                    "VlWU1iA2fjoNOoT+Zt7sMnx00HgTKX+PovVjjT3pX4XnUuvJRdum0JU6tI9ZJmybAEV6743ksPXGx8w3f90bOfTY6+AjReUsJm/Zxa33h8aoGO" +
                    "SoaMzLDauDXb5xps8F99NX+6Z/GReqiRhfy0KccTSFQyNlZxOFMWjVEyHUD3bVu3se18FFdzrMhxpIcmBXheMNr4FtiXjyTKkjwoTfZ6u3Vqty" +
                    "YQXle82ndY4dRGrV9dWPZYWCQZcEUumxnWSYMnXAHJL6jdoMrLjHhKRwnwNJ84E6TLFQl90IleWdfu6iujXjwh4/FmQr39UPtGQaUZfFOQS8n2" +
                    "MISOb0eHbhowUjmU5/Es4ebDlhhHwMUYC+VjquFMbQfOouMb7aGhvpydlLNW0MaUEik9oyAWVqfeILgi1kV3enB/KMlLJ7eYdSkHJoGNcJmLSu" +
                    "4ShapUI5cSWE9rwOENRW69aaTSG/VLHe/unei6bxfw7RXD635J6n6tHwd767R1jxluMrxYQZ7+6a8/ddupofDv+1tlHyC5oh461Ahn4+YqiSgv" +
                    "PBU4DRNF+PBknDqPOmmTJEBhtY/8mI8UJRwKR3InvOWyOG+SmFMBMbtF9XtZUkOC8VWW3dWbUvFKJZHby8qgX0W9iNlY7cspvBrX81OFDqoSWu" +
                    "XJBtSbm5lupxPt3Kd2GLoU2OKJilqItwoIqVLd4vL7pclh35PlF9BwL+ObmydnmZwI9ncBYwa1VSEcVXxsUb10CKteC+xLHMwG3E8ufkQkRYPU" +
                    "0SIaLVw8Sjgb4HTTYLun194iPgR2ayGy7CKXbxPUbdTG76/0UC08gUU9eRqHQtWEuOxalSRbMcmJwRjGqKnvK8j5SECn5M6Is6Eg9zE/pEmvUe" +
                    "WHTj3OyHl+Td5Xln0/3Q38ELh07h5dtcbrC1chl9D3vO/s/fOOM9albyxpFqW7nyMHhwcyZ7/+jR//9uLPRh1phrbjx9w0c+21G5rNo3s0bkS7" +
                    "wc3d9FrXmACaMkG20flUzPaZJjvU+jk983q+VMnKkG7XVNb04/rO197YY8Xtvi1H/vTc2tyDrfk0E3K2r7F1PtUEarZazelfDXPSgC7jssJH7h" +
                    "lq9k2NTt1k9x1ug6/adn42VuwnQW3/lifWtF+TGokGLHUaz+cH177Efc6U/tnXHqaNed9J2VfeBUypx7lXp9fcz5NareYy4MpLvoub3r/jwu5K" +
                    "2w2P1t7r3Hpq9moQeAwAjq0XW1XqOzeZ5FMGt4xqdZpqIE1wNjfj8zRHMzLOGq3tWt2aocDB0v4S2zrqM2k9PGSSz5gNEt8fMGTSNXqmZAbSGo" +
                    "vNoV7gjao2VkQU11WHA1dvvFbHPmRfa64qQiols/FWg8fXOhppJtkqiNUypQjhL+qqTbpH7NVhxlTAdSZ8xtx2SFIkRs6ka30/ST1M65b/R3no" +
                    "KNPyOJZ85htP0sAPvqr+f/PWrqrHlmj9fwNxCuVn";
            default -> throw new IllegalArgumentException(name);
        };
        try (var input = new InflaterInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(value)))) {
            return input.readAllBytes();
        }
    }
}
