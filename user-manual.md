# Instrukcja użytkowania IMGW Monitor

## 1. Informacje wstępne

Aplikacja podzielona jest na **4** zakładki:

- Stacje - odpowiada za dodawanie, usuwanie, wyłączanie i edycję stacji
- Dane - odpowiada za monitorowanie i dodawanie danych dla danej stacji
- Ostrzeżenia - odpowiada za wyświetlanie aktualnych ostrzeżeń hydrologicznych i meteorologicznych
- Ustawienia - odpowiada za możliwość edycji ustawień programu

## 2. Stacje

W tej zakładce można dodać stację klikając przycisk ***Dodaj stację***, a następnie podając poprawne ID z api IMGW, własną nazwę dla tej stacji oraz wybrać jej typ (hydrologiczna lub meteorologiczna) oraz ustawić interwał sprawdzania api w poszukiwaniu nowyszych danych, a także ustawić jej stan (aktywna / nieaktywna)

Po dodaniu stacji znajdzie się ona w tabeli, gdzie można ją *aktywować* / *dezaktywować* klikając w pole wyboru ***Aktywna*** lub edytować jej nazwę lub interwał sprawdzania api klikając dwa razy w stację lub zaznaczając ją i klikając przycisk ***Edytuj*** lub można ją całkowicie usunąć przyciskiem ***Usuń***.

Można także odświeżyć daną stację aby wymusić odwołanie do api ignorując interwał klikając przycisk ***Odśwież***

## 3. Dane

Po dodaniu stacji można zobaczyć pobrane z niej dane w tabeli w tej zakładce po wybraniu jej z menu. Można filtrować zakres tych wpisów suwakiem, który sprawdza różnicę czasu systemowego względem daty i godziny pomiaru danego rekordu.

Można sortować tabelę po każdym z nagłówków tabeli klikając na niego raz (rosnąco) i następnie kolejny aby zmienić kolejność sortowania na odwrotną (malejąco).

Można również wymusić odwołanie się do api ignorując interwał klikając ***Odśwież***.

## 4. Ostrzeżenia

W tej zakładce można podejrzeć aktualne ostrzeżenia hydrologiczne i meteorologiczne wydane przez IMGW.

Można odświeżyć listę przyciskiem ***Odśwież*** aby pominąć domyślny interwał odświeżania ostrzeżeń i sprawdzić czy doszła aktualizacja danych.

Dane można filtrować po poziomie ostrzeżenia (Wszystkie / pomarańczowe i wyżej / tylko czerwone).

Tabelę można sortować po każdym z nagłówków, dokładnie tak samo jak dane z zakładki "Dane".

Na każdy wpis można kliknąć podwójnie aby podejrzeć jego szczegóły tj.:

- poziom
- typ ostrzeżenia
- przez kogo wydane
- kiedy wydano
- do kiedy obowiązuje
- treść komunikatu

## 5. Ustawienia

Ustawienia zarządzają dostępnymi do zmiany opcjami, które użytkownik może dowolnie modyfikować.

### Sposób zapisywania danych

Tu zawierają się dwa dostępne tryby zapisu danych (do pliku JSON lub do Bazy Danych SQLite).

*Zmiana wymaga zastosowania przyciskiem i resetu aplikacji*.

UWAGA! Dane zapisane z pliku nie przenoszą się do bazy danych i vice versa!

### Zakres monitorowania danych

Tu zawierają się opcje monitorowania, co tabela "Dane" ma wyświetlać. *(Dane zawsze są zbierane w całości i tylko modyfikowane jest co się wyświetla w interfejsie użytkownika)*.

*Zmiana tych ustawień wymaga zastosowania*.

### Harmonogram

Tu znajduje się domyślne ustawienie interwału, co ile minut mają być odświeżane nowe stacje *(o ile użytkownik nie zmienił tego ręcznie)*. Minimalna wartość to **5 minut** a maksymalna **30 minut**.

*Zmiana wymaga zastosowania*.

### Połączenie z serwerem IMGW

Tu można modyfikować zachowanie połączenia z API (przydaje się głównie w przypadkach kiedy serwer jest przeciążony lub połączenie sieciowe użytkownika jest wolne).

***Czas oczekiwania na odpowiedź*** to maksymalny czas jaki aplikacja wyznacza aby dostała odpowiedź od serwera, jeśli ten czas minie, ponawia próbę tyle razy ile wskazuje ***liczba ponowień***.

*Zmiana tych ustawień wymaga zastosowania*.

### Dziennik zdarzeń

Tu można modyfikować, jakie tylko zdarzenia (i te poważniejsze od tych) mają się pojawiać w dzienniku zdarzeń (w folderze logs).

Zdefiniowane jest pięć poziomów:

- TRACE
- DEBUG
- INFO
- WARN
- ERROR

### Czyszczenie historii danych

Tu można usuwać historię danych starszych niż określony dzień w podanym parametrze. Po określeniu parametru należy kliknąć w "Usuń dane starsze niż".

Można też usunąć wszystkie dane pomiarowe, co sprawi że usuną się wszystkie wpisy z "Dane", "Stacje" i "Ostrzeżenia".

### Reset

Tu można przywrócić domyślne ustawienia aplikacji, które zostały zdefiniowane przy pierwszym uruchomieniu.

*Wymaga zastosowania i resetu aplikacji*.
